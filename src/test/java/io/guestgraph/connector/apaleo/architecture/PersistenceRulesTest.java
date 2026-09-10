package io.guestgraph.connector.apaleo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.guestgraph.connector.apaleo.state.ConnectionAgnostic;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;

/**
 * The engine's persistence guardrails, carried over: every query explicit and scoped, no repository
 * scaffolding, no ad-hoc EntityManager queries, no JdbcClient, JPA confined to {@code state}. One
 * instance serves many connections, so the engine's tenant-parameter rule returns with the
 * connection in the tenant's place.
 */
class PersistenceRulesTest {

  static JavaClasses appClasses;

  static final DescribedPredicate<JavaClass> SPRING_DATA_REPOSITORY =
      new DescribedPredicate<>("are Spring Data repositories") {
        @Override
        public boolean test(JavaClass javaClass) {
          return javaClass.isAssignableTo(Repository.class);
        }
      };

  @BeforeAll
  static void importClasses() {
    appClasses =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            // Spring AOT artifacts are generated from the reviewed source.
            .withImportOption(location -> !location.contains("__"))
            .importPackages("io.guestgraph.connector.apaleo");
  }

  @Test
  void noRepositoryScaffolding() {
    noClasses()
        .should()
        .beAssignableTo(CrudRepository.class)
        .orShould()
        .beAssignableTo(PagingAndSortingRepository.class)
        .because("derived and generic repository methods bypass the reviewed @Query surface")
        .check(appClasses);
  }

  @Test
  void everyRepositoryMethodIsConnectionScoped() {
    methods()
        .that()
        .areDeclaredInClassesThat(SPRING_DATA_REPOSITORY)
        .should(
            new ArchCondition<>(
                "take a connectionId parameter or be @ConnectionAgnostic with a justification") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                if (method.isAnnotatedWith(ConnectionAgnostic.class)) {
                  return;
                }
                boolean hasConnectionId =
                    Arrays.stream(method.reflect().getParameters())
                        .map(Parameter::getName)
                        .anyMatch("connectionId"::equals);
                if (!hasConnectionId) {
                  events.add(
                      SimpleConditionEvent.violated(
                          method,
                          method.getFullName()
                              + " has no connectionId parameter and no @ConnectionAgnostic"));
                }
              }
            })
        .because("a repository query without a connection predicate reads across connections")
        .check(appClasses);
  }

  @Test
  void everyRepositoryMethodDeclaresAnExplicitQuery() {
    methods()
        .that()
        .areDeclaredInClassesThat(SPRING_DATA_REPOSITORY)
        .should()
        .beAnnotatedWith(Query.class)
        .because("every repository method is explicit JPQL or SQL, reviewable in one place")
        .check(appClasses);
  }

  @Test
  void noAdHocEntityManagerQueries() {
    classes()
        .should(
            new ArchCondition<>("not create ad-hoc EntityManager queries") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                javaClass.getMethodCallsFromSelf().stream()
                    .filter(
                        call ->
                            "jakarta.persistence.EntityManager"
                                    .equals(call.getTargetOwner().getName())
                                && call.getName().startsWith("create"))
                    .forEach(
                        call ->
                            events.add(
                                SimpleConditionEvent.violated(
                                    javaClass,
                                    call.getDescription()
                                        + " — EntityManager.create*Query bypasses the reviewed"
                                        + " @Query surface")));
              }
            })
        .because("all query text lives in repository @Query annotations")
        .check(appClasses);
  }

  @Test
  void noJdbcClient() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
        .because("raw SQL escapes the @Query guardrails; the connector has no exemption")
        .check(appClasses);
  }

  @Test
  void onlyStateDependsOnJpa() {
    noClasses()
        .that()
        .resideOutsideOfPackage("io.guestgraph.connector.apaleo.state..")
        .should()
        .dependOnClassesThat(
            new DescribedPredicate<>("belong to jakarta.persistence or Hibernate") {
              @Override
              public boolean test(JavaClass javaClass) {
                String name = javaClass.getPackageName();
                return name.startsWith("jakarta.persistence") || name.startsWith("org.hibernate");
              }
            })
        .because("the clients, the mapping and the runs are storage-agnostic")
        .check(appClasses);
  }
}
