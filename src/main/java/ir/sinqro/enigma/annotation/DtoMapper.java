package ir.sinqro.enigma.annotation;

import java.lang.annotation.*;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DtoMapper {
    Class<?> value();
}
