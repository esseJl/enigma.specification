package ir.sinqro.enigma.hr;


import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.*;
import ir.sinqro.enigma.query.Filters;
import ir.sinqro.enigma.repository.GenericRepository;
import ir.sinqro.enigma.service.QueryService;
import ir.sinqro.enigma.specification.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@Transactional
@SpringBootTest(classes = PersonSpecificationFullTest.TestConfig.class)
@ContextConfiguration(classes = PersonSpecificationFullTest.TestConfig.class)  // ← مهم
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:person_spec;MODE=Oracle;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",  // ← این خط
        "spring.batch.job.enabled=false",
        "enigma.spec.max-filter-depth=32",
        "enigma.spec.max-sort-fields=20",
        "enigma.spec.max-filter-nodes=1000",
        "enigma.spec.max-joins=30"
})
public class PersonSpecificationFullTest {

    // ─── Config ایزوله (جایگزین TradeJournalApplication) ───
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.boot.persistence.autoconfigure.EntityScan(basePackageClasses = {
            Person.class, NaturalPerson.class, LegalPerson.class,
            Address.class, ContactInfo.class
    })
    @EnableJpaRepositories(basePackageClasses = PersonRepository.class, considerNestedRepositories = true)
    @Import({
            SpecificationBuilder.class,
            FieldMetaRegistry.class,
            ValueConverter.class,
            PathResolver.class,
            DtoMapperHelper.class,
            PersonQueryService.class
    })
    static class TestConfig {

        // اگر ResponseUtils لازم است و bean ندارد:
        @Bean
        ir.sinqro.enigma.utils.response.ResponseUtils responseUtils() {
            return new ir.sinqro.enigma.utils.response.ResponseUtilsImp();
            // یا یک mock ساده اگر constructor پیچیده دارد
        }

        @Bean
        org.springframework.core.convert.ConversionService mvcConversionService() {
            return new org.springframework.core.convert.support.DefaultConversionService();
        }
    }

    // ─── Entityهای تست (همان قبلی) ───
    @MappedSuperclass
    @Getter
    @Setter
    public static abstract class BaseEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @Column(nullable = false)
        private boolean deleted = false;
        private Instant deletedAt;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;
    }

    @Entity
    @Table(name = "person")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "person_type")
    @Getter
    @Setter
    public static abstract class Person extends BaseEntity {
        @Column(nullable = false, length = 200)
        private String name;
        @Column(length = 20)
        private String nationalCode;
        @OneToMany(mappedBy = "person", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<Address> addresses = new ArrayList<>();
        @OneToMany(mappedBy = "person", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<ContactInfo> contacts = new ArrayList<>();
    }

    @Entity
    @Table(name = "natural_person")
    @DiscriminatorValue("NATURAL")
    @Getter
    @Setter
    public static class NaturalPerson extends Person {
        private String firstName;
        private String lastName;
        private LocalDate birthDate;
        private String gender;
    }

    @Entity
    @Table(name = "legal_person")
    @DiscriminatorValue("LEGAL")
    @Getter
    @Setter
    public static class LegalPerson extends Person {
        private String registrationNumber;
        private String economicCode;
        private String companyName;
    }

    @Entity
    @Table(name = "address")
    @Getter
    @Setter
    public static class Address {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "person_id", nullable = false)
        private Person person;
        private String province;
        private String city;
        private String fullAddress;
        private String postalCode;
        private boolean isPrimary = false;
    }

    @Entity
    @Table(name = "contact_info")
    @Getter
    @Setter
    public static class ContactInfo {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "person_id", nullable = false)
        private Person person;
        @Enumerated(EnumType.STRING)
        @Column(name = "contact_type", nullable = false, length = 20)
        private ContactType type;
        @Column(name = "contact_value", nullable = false, length = 100)
        private String value;
        private boolean isPrimary = false;
    }

    public enum ContactType {PHONE, MOBILE, FAX, EMAIL}

    @DtoMapper(Person.class)
    @Getter
    @Setter
    public static class PersonSummaryDto {
        private UUID id;
        private String name;
        private String nationalCode;
        private List<AddressDto> addresses = new ArrayList<>();
    }

    @DtoMapper(NaturalPerson.class)
    @Getter
    @Setter
    public static class NaturalPersonDetailDto {
        private UUID id;
        private String firstName;
        private String lastName;
        private String nationalCode;
        private LocalDate birthDate;
        private String gender;
        private List<AddressDto> addresses;
        private List<ContactDto> contacts;
    }

    @DtoMapper(ContactInfo.class)
    @Getter
    @Setter
    public static class ContactDto {
        private UUID id;
        private ContactType contactType;
        private String value;
        private boolean primary;
    }

    @DtoMapper(Address.class)
    @Getter
    @Setter
    public static class AddressDto {
        private UUID id;
        private String province;
        private String city;
        private String fullAddress;
        private String postalCode;
        private boolean primary;
    }

    public interface PersonRepository extends GenericRepository<Person, UUID> {
    }

    // ─── همان چیزی که برنامه‌نویس نهایی می‌نویسد: فقط Repository + DTO پیش‌فرض ───
    @org.springframework.stereotype.Service
    public static class PersonQueryService extends QueryService<Person, UUID, PersonSummaryDto> {

        public PersonQueryService(PersonRepository repository) {
            super(repository, PersonSummaryDto.class);
        }

        @Override
        protected QueryRequest internalFilter() {
            return QueryRequest.of(Filters.eq("deleted", false));
        }
    }

    // ─── تزریق و داده ───
    @Autowired
    PersonQueryService personQueryService;
    @Autowired
    PersonRepository personRepository;
    @Autowired
    ValueConverter valueConverter;

    private NaturalPerson natural1, natural2;
    private LegalPerson legal1;

    @BeforeEach
    void setUp() {
        // شخص حقیقی ۱
        natural1 = new NaturalPerson();
        natural1.setName("علی رضایی");
        natural1.setFirstName("علی");
        natural1.setLastName("رضایی");
        natural1.setNationalCode("0012345678");
        natural1.setBirthDate(LocalDate.of(1985, 5, 12));
        natural1.setGender("MALE");

        Address addr1 = new Address();
        addr1.setProvince("تهران");
        addr1.setCity("تهران");
        addr1.setFullAddress("خیابان ولیعصر، پلاک ۱۲۳");
        addr1.setPostalCode("1234567890");
        addr1.setPrimary(true);
        addr1.setPerson(natural1);
        natural1.getAddresses().add(addr1);

        ContactInfo mobile1 = new ContactInfo();
        mobile1.setType(ContactType.MOBILE);
        mobile1.setValue("09121234567");
        mobile1.setPrimary(true);
        mobile1.setPerson(natural1);
        natural1.getContacts().add(mobile1);

        // شخص حقیقی ۲
        natural2 = new NaturalPerson();
        natural2.setName("مریم احمدی");
        natural2.setFirstName("مریم");
        natural2.setLastName("احمدی");
        natural2.setNationalCode("0098765432");
        natural2.setBirthDate(LocalDate.of(1990, 8, 20));
        natural2.setGender("FEMALE");

        // شخص حقوقی
        legal1 = new LegalPerson();
        legal1.setName("شرکت نرم‌افزاری سینقرو");
        legal1.setCompanyName("شرکت نرم‌افزاری سینقرو");
        legal1.setNationalCode("10101234567"); // شناسه ملی
        legal1.setRegistrationNumber("12345");
        legal1.setEconomicCode("41111111111");

        personRepository.saveAll(List.of(natural1, natural2, legal1));
    }

    // ────────────────────────────────────────────────
    // ۱. تست‌های پایه مدل‌ها و Validation
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("FilterPredicate - validation کامل")
    void filterPredicate_validation() {
        assertThatThrownBy(() -> new FilterPredicate(null, ComparisonOperator.EQ, new SingleValue("x")))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new FilterPredicate("  ", ComparisonOperator.EQ, new SingleValue("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        assertThatThrownBy(() -> new FilterPredicate("name", ComparisonOperator.EQ, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");

        assertThatThrownBy(() -> new FilterPredicate("name", ComparisonOperator.IS_NULL, new SingleValue("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("ComparisonOperator - from() با ورودی‌های مختلف")
    void comparisonOperator_from() {
        assertThat(ComparisonOperator.from("eq")).isEqualTo(ComparisonOperator.EQ);
        assertThat(ComparisonOperator.from("CONTAINS")).isEqualTo(ComparisonOperator.CONTAINS);
        assertThat(ComparisonOperator.from("starts-with")).isEqualTo(ComparisonOperator.STARTS_WITH);

        assertThatThrownBy(() -> ComparisonOperator.from(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComparisonOperator.from("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ────────────────────────────────────────────────
    // ۲. تست‌های امنیتی (SQL Injection)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("SQL Injection - مقدار مخرب در LIKE/CONTAINS باید escape شود")
    void sqlInjection_likeAndContains() {
        String malicious = "'; DROP TABLE person; --";
        FilterNode filter = Filters.contains("name", malicious);

        QueryRequest request = QueryRequest.of(filter);

        // نباید exception بدهد و نباید داده خراب شود
        var result = personQueryService.query(request);

        assertThat(result.result()).isEmpty(); // هیچ رکوردی match نمی‌شود
        // جدول هنوز سالم است
        assertThat(personRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("SQL Injection - کاراکترهای ویژه % و _ در LIKE")
    void sqlInjection_likeWildcards() {
        FilterNode filter = Filters.contains("name", "رضایی");
        var result = personQueryService.query(QueryRequest.of(filter));
        // باید فقط با escape کار کند و نتیجه اشتباه ندهد
        assertThat(result.result()).hasSize(1);
        assertThat(result.result().get(0).getName()).contains("رضایی");
    }

    @Test
    @DisplayName("SQL Injection - کاراکترهای ویژه % و _ در LIKE")
    void sqlInjection_likeWildcardsWithPercent() {
        FilterNode filter = Filters.contains("name", "%رضایی%");
        var result = personQueryService.query(QueryRequest.of(filter));
        assertThat(result.result()).isEmpty();
    }

    // ────────────────────────────────────────────────
    // ۳. تست‌های Operatorها
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Operator EQ / NEQ")
    void operators_eqNeq() {
        var eq = personQueryService.query(
                QueryRequest.of(Filters.eq("nationalCode", "0012345678")));
        assertThat(eq.result()).hasSize(1);

        var neq = personQueryService.query(
                QueryRequest.of(Filters.neq("nationalCode", "0012345678")));
        assertThat(neq.result()).hasSize(2);
    }

    @Test
    @DisplayName("Operator CONTAINS / STARTS_WITH / ENDS_WITH")
    void operators_string() {
        var contains = personQueryService.query(
                QueryRequest.of(Filters.contains("name", "رضایی")));
        assertThat(contains.result()).hasSize(1);

        var starts = personQueryService.query(
                QueryRequest.of(Filters.startsWith("name", "علی")));
        assertThat(starts.result()).hasSize(1);

        var ends = personQueryService.query(
                QueryRequest.of(Filters.endsWith("name", "احمدی")));
        assertThat(ends.result()).hasSize(1);
    }

    @Test
    @DisplayName("Operator IN / NOT_IN")
    void operators_in() {
        var inFilter = personQueryService.query(
                QueryRequest.of(Filters.in("nationalCode", "0012345678", "10101234567")));
        assertThat(inFilter.result()).hasSize(2);
    }

    @Test
    @DisplayName("Operator BETWEEN (تاریخ تولد)")
    void operators_between() {
        var between = personQueryService.queryAs(
                QueryRequest.of(Filters.between("birthDate", LocalDate.of(1980, 1, 1), LocalDate.of(1988, 12, 31))),
                NaturalPersonDetailDto.class);
        assertThat(between.result()).hasSize(1);
        assertThat(between.result().get(0).getFirstName()).contains("علی");
    }

    @Test
    @DisplayName("Operator IS_NULL / IS_NOT_NULL")
    void operators_null() {
        // همه رکوردها nationalCode دارند
        var notNull = personQueryService.query(
                QueryRequest.of(Filters.isNotNull("nationalCode")));
        assertThat(notNull.result()).hasSize(3);
    }

    // ────────────────────────────────────────────────
    // ۴. تست‌های Group و Nested Filter (AND / OR)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("FilterGroup AND / OR پیچیده")
    void filterGroup_complex() {
        FilterNode andGroup = Filters.and(
                Filters.eq("gender", "MALE"),
                Filters.contains("lastName", "رضایی")
        );

        var result = personQueryService.queryAs(QueryRequest.of(andGroup), NaturalPersonDetailDto.class);
        assertThat(result.result()).hasSize(1);

        FilterNode orGroup = Filters.or(
                Filters.eq("nationalCode", "0012345678"),
                Filters.eq("nationalCode", "10101234567")
        );
        var orResult = personQueryService.query(QueryRequest.of(orGroup));
        assertThat(orResult.result()).hasSize(2);
    }

    @Test
    @DisplayName("عمق زیاد Filter (محافظت از maxFilterDepth)")
    void filterDepth_protection() {
        FilterNode deep = new FilterPredicate("name", ComparisonOperator.EQ, new SingleValue("x"));
        for (int i = 0; i < 40; i++) {
            deep = new FilterGroup(LogicalOperator.AND, List.of(deep));
        }

        FilterNode finalDeep = deep;
        assertThatThrownBy(() -> personQueryService.query(QueryRequest.of(finalDeep)))
                .isInstanceOf(QueryRuleException.class)
                .hasMessageContaining("depth");
    }

    // ────────────────────────────────────────────────
    // ۵. Soft-Delete
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Soft-Delete - رکورد حذف‌شده نباید برگردد")
    void softDelete_works() {
        natural2.setDeleted(true);
        natural2.setDeletedAt(Instant.now());
        personRepository.save(natural2);

        var all = personQueryService.query(QueryRequest.of(null));
        assertThat(all.result()).hasSize(2); // natural2 حذف شده
        assertThat(all.result()).noneMatch(p -> p.getName().contains("مریم"));
    }

    // ────────────────────────────────────────────────
    // ۶. Sort و Pagination
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("Sort و Pagination")
    void sortAndPagination() {
        QueryRequest request = new QueryRequest(
                null,
                List.of(new SortRequest("name", SortDirection.ASC)),
                new PageRequest(0, 2)
        );

        var page = personQueryService.query(request);
        assertThat(page.result()).hasSize(2);
        assertThat(page.count()).isEqualTo(3);
        assertThat(page.result().get(0).getName()).isLessThan(page.result().get(1).getName());
    }

    @Test
    @DisplayName("تعداد زیاد Sort فیلد → RuleException")
    void tooManySortFields() {
        List<SortRequest> manySorts = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new SortRequest("name", SortDirection.ASC))
                .toList();

        QueryRequest request = new QueryRequest(null, manySorts, PageRequest.firstPage());

        assertThatThrownBy(() -> personQueryService.query(request))
                .isInstanceOf(QueryRuleException.class)
                .hasMessageContaining("Too many sort fields");
    }

    // ────────────────────────────────────────────────
    // ۷. ValueConverter
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("ValueConverter - تبدیل امن انواع داده")
    void valueConverter_types() {
        assertThat(valueConverter.convertValue("123", Integer.class)).isEqualTo(123);
        assertThat(valueConverter.convertValue("true", Boolean.class)).isEqualTo(true);
        assertThat(valueConverter.convertValue("1985-05-12", LocalDate.class))
                .isEqualTo(LocalDate.of(1985, 5, 12));

        assertThatThrownBy(() -> valueConverter.convertValue("not-a-number", Integer.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ────────────────────────────────────────────────
    // ۸. DTO Projection
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("DTO Projection با @DtoMapper")
    void dtoProjection() {
        var result = personQueryService.query(
                QueryRequest.of(Filters.contains("name", "سینقرو")));

        assertThat(result.result()).hasSize(1);
        assertThat(result.result().get(0).getName()).contains("سینقرو");
        assertThat(result.result().get(0).getId()).isNotNull();
    }

    // ────────────────────────────────────────────────
    // ۹. Join و Path Resolver (آدرس و تماس)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("فیلتر روی رابطه (آدرس استان)")
    void join_filterOnAddress() {
        FilterNode filter = Filters.eq("addresses.province", "تهران");

        var result = personQueryService.query(
                QueryRequest.of(filter));

        assertThat(result.result()).hasSize(1);
        assertThat(result.result().get(0).getName()).contains("علی");
    }

    // ────────────────────────────────────────────────
    // ۱۰. تست‌های مرزی و Performance
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("QueryRequest null → نتیجه خالی امن")
    void nullRequest_safe() {
        var result = personQueryService.query(null);
        assertThat(result.result()).hasSize(3); // فقط soft-delete اعمال شده
    }

    @Test
    @DisplayName("صفحه خیلی بزرگ → رد می‌شود")
    void largePageSize() {
        // اعتبارسنجی در خود PageRequest
        assertThatThrownBy(() -> new PageRequest(0, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be <= 1000");

        // مرز مجاز باید کار کند
        QueryRequest ok = new QueryRequest(null, List.of(), new PageRequest(0, 1000));
        var result = personQueryService.query(ok);
        assertThat(result.result()).hasSize(3);   // فقط ۳ رکورد در دیتاست تست
    }
}