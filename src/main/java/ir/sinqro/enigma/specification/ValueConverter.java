package ir.sinqro.enigma.specification;


import ir.sinqro.enigma.model.FilterValue;
import ir.sinqro.enigma.model.MultiValue;
import ir.sinqro.enigma.model.RangeValue;
import ir.sinqro.enigma.model.SingleValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;


@Component
public class ValueConverter {

    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy/MM/dd[['T'][ ]HH:mm[:ss][.SSS]]")
                    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                    .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                    .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.SMART)
    );

    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    );

    private static final List<DateTimeFormatter> LOCAL_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_TIME,
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm")
    );

    private final ConversionService conversionService;
    private final ZoneId zoneId;
    private final int maxInSize;


    public Object convertSingle(FilterValue filterValue, Class<?> targetType) {
        requireType(filterValue, FilterValue.ValueType.SINGLE);
        return convertValue(((SingleValue) filterValue).value(), targetType);
    }


    public ValueConverter(ConversionService conversionService,
                          @Value("${enigma.timezone:UTC}") String zoneIdStr,
                          @Value("${enigma.spec.max-in-size:500}") int maxInSize) {
        this.conversionService = Objects.requireNonNull(conversionService,
                "ConversionService must not be null");
        this.zoneId = ZoneId.of(zoneIdStr);
        this.maxInSize = maxInSize > 0 ? maxInSize : 500;
    }

    public Collection<?> convertMulti(FilterValue filterValue, Class<?> targetType) {
        requireType(filterValue, FilterValue.ValueType.MULTI);
        List<?> values = ((MultiValue) filterValue).values();
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("MULTI value must contain at least one element");
        }
        if (values.size() > maxInSize) {
            throw new IllegalArgumentException(
                    "IN/MULTI list exceeds maximum allowed size of " + maxInSize);
        }
        return values.stream()
                .map(v -> convertValue(v, targetType))
                .collect(Collectors.toList());
    }

    public RangeValue requireRange(FilterValue filterValue) {
        requireType(filterValue, FilterValue.ValueType.RANGE);
        return (RangeValue) filterValue;
    }

    public Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        Objects.requireNonNull(targetType, "targetType must not be null");

        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }

        try {
            if (value instanceof String strValue) {
                Object temporal = tryConvertTemporal(strValue.trim(), targetType);
                if (temporal != null) {
                    return temporal;
                }
            }

            // باید قبل از شاخهٔ عددی/primitive بررسی شود: چون boolean.class هم یک
            // primitive است، اگر این شرط بعد از شرط isPrimitive() قرار بگیرد،
            // فیلدهای boolean اولیه (نه Boolean) هرگز به parseBoolean() نمی‌رسند و
            // به‌جایش وارد convertNumber() می‌شوند. نتیجه: رفتار متفاوت و ناسازگار
            // بین «boolean» و «Boolean» برای همان مقدار ورودی (مثلاً "y"/"n" که فقط
            // در parseBoolean پشتیبانی می‌شود، نه در مسیر عددی/ConversionService).
            if (targetType == Boolean.class || targetType == boolean.class) {
                return parseBoolean(value);
            }

            if (isNumericType(targetType)) {
                return convertNumber(value, targetType);
            }

            if (Enum.class.isAssignableFrom(targetType)) {
                return convertEnum(value, targetType);
            }

            if (conversionService.canConvert(value.getClass(), targetType)) {
                return conversionService.convert(value, targetType);
            }

            if (!(value instanceof String)) {
                return convertValue(value.toString(), targetType);
            }

            throw new IllegalArgumentException(
                    "Unsupported conversion from " + value.getClass().getSimpleName()
                            + " to " + targetType.getSimpleName());
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Cannot convert value '" + value + "' to " + targetType.getSimpleName()
                            + ". Cause: " + ex.getMessage(), ex);
        }
    }

    /**
     * true فقط برای Number و انواع primitive عددی. به‌صراحت boolean/char را
     * کنار می‌گذارد چون این‌ها primitive هستند اما معنای عددی ندارند و نباید با
     * BigDecimal/Long/... تلاش برای parse شوند.
     */
    private static boolean isNumericType(Class<?> targetType) {
        if (Number.class.isAssignableFrom(targetType)) {
            return true;
        }
        return targetType.isPrimitive()
                && targetType != boolean.class
                && targetType != char.class
                && targetType != void.class;
    }

    private Object tryConvertTemporal(String str, Class<?> targetType) {
        if (str.isEmpty()) {
            return null;
        }
        if (targetType == Instant.class) {
            return parseInstant(str);
        }
        if (targetType == LocalDateTime.class) {
            return parseLocalDateTime(str);
        }
        if (targetType == LocalDate.class) {
            return parseLocalDate(str);
        }
        if (targetType == LocalTime.class) {
            return parseLocalTime(str);
        }
        if (targetType == OffsetDateTime.class) {
            return OffsetDateTime.parse(str, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (targetType == ZonedDateTime.class) {
            return ZonedDateTime.parse(str, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        }
        return null;
    }

    private Instant parseInstant(String str) {
        if (str.endsWith("Z") || hasOffset(str)) {
            return Instant.parse(str);
        }
        LocalDateTime ldt = parseLocalDateTime(str);
        return ldt.atZone(zoneId).toInstant();
    }

    private LocalDateTime parseLocalDateTime(String str) {
        if (str.contains("T") && (str.endsWith("Z") || hasOffset(str))) {
            return LocalDateTime.ofInstant(Instant.parse(normalizeInstant(str)), zoneId);
        }
        for (DateTimeFormatter fmt : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(str, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        LocalDate date = parseLocalDate(str);
        return date.atStartOfDay();
    }

    private LocalDate parseLocalDate(String str) {
        if (str.contains("T") || str.contains(" ")) {
            return parseLocalDateTime(str).toLocalDate();
        }
        String normalized = str.replace('/', '-');
        for (DateTimeFormatter fmt : LOCAL_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalized, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter fmt : LOCAL_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(str, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new DateTimeParseException("Unparseable date: " + str, str, 0);
    }

    private LocalTime parseLocalTime(String str) {
        if (str.contains("T")) {
            return parseLocalDateTime(str).toLocalTime();
        }
        for (DateTimeFormatter fmt : LOCAL_TIME_FORMATTERS) {
            try {
                return LocalTime.parse(str, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new DateTimeParseException("Unparseable time: " + str, str, 0);
    }

    private static boolean hasOffset(String str) {
        return str.matches(".*[+-]\\d{2}:?\\d{2}$");
    }

    private static String normalizeInstant(String str) {
        if (str == null || str.isBlank()) {
            return str;
        }
        str = str.trim();
        if (str.length() == 10 && (str.contains("-") || str.contains("/"))) {
            return str.replace('/', '-') + "T00:00:00Z";
        }
        if (str.endsWith("Z") || hasOffset(str)) {
            return str;
        }
        if (str.contains("T")) {
            return str + "Z";
        }
        return str;
    }

    private Object convertNumber(Object value, Class<?> targetType) {
        String s = value.toString().trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty number string");
        }
        try {
            if (targetType == Long.class || targetType == long.class) return Long.valueOf(s);
            if (targetType == Integer.class || targetType == int.class) return Integer.valueOf(s);
            if (targetType == Short.class || targetType == short.class) return Short.valueOf(s);
            if (targetType == Byte.class || targetType == byte.class) return Byte.valueOf(s);
            if (targetType == Double.class || targetType == double.class) return Double.valueOf(s);
            if (targetType == Float.class || targetType == float.class) return Float.valueOf(s);
            if (targetType == BigDecimal.class) return new BigDecimal(s);
            if (targetType == BigInteger.class) return new BigInteger(s);
            if (conversionService.canConvert(String.class, targetType)) {
                return conversionService.convert(s, targetType);
            }
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number: '" + s + "'", ex);
        }
    }

    private static Boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "1", "yes", "y", "on" -> Boolean.TRUE;
            case "false", "0", "no", "n", "off" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("Cannot parse boolean from: '" + value + "'");
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertEnum(Object value, Class<?> targetType) {
        Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
        String name = value.toString().trim();
        try {
            return Enum.valueOf(enumType, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            try {
                return Enum.valueOf(enumType, name);
            } catch (IllegalArgumentException ex2) {
                throw new IllegalArgumentException(
                        "Unknown enum constant '" + name + "' for " + enumType.getSimpleName(), ex2);
            }
        }
    }

    private static void requireType(FilterValue filterValue, FilterValue.ValueType expected) {
        if (filterValue == null) {
            throw new IllegalArgumentException("FilterValue must not be null");
        }
        if (filterValue.type() != expected) {
            throw new IllegalArgumentException(
                    "Expected " + expected + " value, got " + filterValue.type());
        }
    }
}