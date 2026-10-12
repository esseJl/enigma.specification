package ir.sinqro.enigma.utils.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

public interface ResponseUtils {

    Map<String, Object> getResponsePageable(Page<? extends Object> list);

    Object getResponse(List<? extends Object> list);
}
