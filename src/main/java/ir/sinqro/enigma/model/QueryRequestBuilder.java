package ir.sinqro.enigma.model;

import java.util.ArrayList;
import java.util.List;

public final class QueryRequestBuilder {
    private FilterNode filter;
    private final List<SortRequest> sorts = new ArrayList<>();
    private PageRequest page = PageRequest.firstPage();

    public QueryRequestBuilder filter(FilterNode filter) {
        this.filter = filter;
        return this;
    }

    public QueryRequestBuilder sortAsc(String field) {
        sorts.add(SortRequest.asc(field));
        return this;
    }

    public QueryRequestBuilder sortDesc(String field) {
        sorts.add(SortRequest.desc(field));
        return this;
    }

    public QueryRequestBuilder page(int page, int size) {
        this.page = new PageRequest(page, size);
        return this;
    }

    public QueryRequest build() {
        return new QueryRequest(filter, sorts, page);
    }
}
