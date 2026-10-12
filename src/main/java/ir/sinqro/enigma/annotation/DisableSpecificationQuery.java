package ir.sinqro.enigma.annotation;

import java.lang.annotation.*;

@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DisableSpecificationQuery {
    String reason() default "Field is not allowed in dynamic queries";
}
