package org.sil.kestra.plugin.netsuite;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Walks NetSuite's paged responses ({@code items}, {@code hasMore}, {@code offset}, {@code totalResults})
 * and returns the rows according to a {@link FetchType}.
 */
final class Rows {
    private Rows() {
    }

    @FunctionalInterface
    public interface NextPage {
        Map<String, Object> fetch(long offset) throws Exception;
    }

    public record Result(List<Map<String, Object>> rows, Map<String, Object> row, URI uri, long size) {
    }

    /** Whether a response body is one of NetSuite's paged collections. */
    public static boolean isPage(Object body) {
        return body instanceof Map<?, ?> map && map.containsKey("items") && map.containsKey("hasMore");
    }

    /**
     * @param firstPage the already-fetched first page
     * @param paginate  whether to keep requesting pages while {@code hasMore} is true
     * @param next      fetches the page starting at the given offset
     */
    public static Result collect(RunContext runContext, FetchType fetchType, Map<String, Object> firstPage,
                                 boolean paginate, NextPage next) throws Exception {
        return switch (fetchType) {
            case NONE -> new Result(null, null, null,
                ((Number) firstPage.getOrDefault("totalResults", items(firstPage).size())).longValue());
            case FETCH_ONE -> {
                List<Map<String, Object>> items = items(firstPage);
                yield new Result(null, items.isEmpty() ? null : items.getFirst(), null, items.isEmpty() ? 0 : 1);
            }
            case FETCH -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                walk(firstPage, paginate, next, rows::add);
                yield new Result(rows, null, null, rows.size());
            }
            case STORE -> {
                File file = runContext.workingDir().createTempFile(".ion").toFile();
                long[] count = {0};
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), FileSerde.BUFFER_SIZE)) {
                    walk(firstPage, paginate, next, row -> {
                        FileSerde.write(out, row);
                        count[0]++;
                    });
                }
                yield new Result(null, null, runContext.storage().putFile(file), count[0]);
            }
        };
    }

    private interface RowConsumer {
        void accept(Map<String, Object> row) throws Exception;
    }

    private static void walk(Map<String, Object> page, boolean paginate, NextPage next, RowConsumer consumer) throws Exception {
        while (true) {
            List<Map<String, Object>> items = items(page);
            for (Map<String, Object> row : items) {
                consumer.accept(row);
            }
            if (!paginate || !Boolean.TRUE.equals(page.get("hasMore")) || items.isEmpty()) {
                return;
            }
            long offset = page.get("offset") instanceof Number n ? n.longValue() : 0;
            page = next.fetch(offset + items.size());
        }
    }

    /** The page's items, with NetSuite's per-item {@code links} removed. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> page) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.getOrDefault("items", List.of());
        items.forEach(row -> row.remove("links"));
        return items;
    }
}
