package ir.sinqro.enigma.model;

import java.util.List;

public record QueryRequest(FilterNode filter, List<SortRequest> sort, PageRequest page) {

    public QueryRequest {
        sort = sort == null ? List.of() : List.copyOf(sort);
        page = page == null ? PageRequest.firstPage() : page;
    }

    public static QueryRequest of(FilterNode filter) {
        return new QueryRequest(
                filter,
                List.of(),
                PageRequest.firstPage());
    }
}