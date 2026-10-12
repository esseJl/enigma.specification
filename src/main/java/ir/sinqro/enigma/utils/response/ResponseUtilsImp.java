package ir.sinqro.enigma.utils.response;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResponseUtilsImp implements ResponseUtils {
    @Override
    public Map<String, Object> getResponsePageable(Page<? extends Object> list) {
        Map<String, Object> response = new HashMap<>();
        response.put("result", list.getContent());
        response.put("currentPage", list.getNumber());
        response.put("count", list.getTotalElements());
        response.put("totalPages", list.getTotalPages());
        response.put("size", list.getSize());
        return response;
    }

    @Override
    public Object getResponse(List<?> list) {
        return list;
    }
}
