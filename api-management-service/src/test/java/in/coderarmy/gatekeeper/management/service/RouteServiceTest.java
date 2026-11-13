package in.coderarmy.gatekeeper.management.service;

import in.coderarmy.gatekeeper.management.dto.requestDto.RouteRequest;
import in.coderarmy.gatekeeper.management.dto.requestDto.RouteStatusRequest;
import in.coderarmy.gatekeeper.management.dto.responseDto.RouteResponse;
import in.coderarmy.gatekeeper.management.entity.ApiRoute;
import in.coderarmy.gatekeeper.management.entity.Organization;
import in.coderarmy.gatekeeper.management.entity.OrganizationStatus;
import in.coderarmy.gatekeeper.management.entity.RouteConfigurationVersion;
import in.coderarmy.gatekeeper.management.exception.ApiException;
import in.coderarmy.gatekeeper.management.repository.ApiRouteRepository;
import in.coderarmy.gatekeeper.management.repository.ControlPlaneAuditRecordRepository;
import in.coderarmy.gatekeeper.management.repository.RouteConfigurationVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private ApiRouteRepository apiRouteRepository;

    @Mock
    private RouteConfigurationVersionRepository versionRepository;

    @Mock
    private ControlPlaneAuditRecordRepository auditRecordRepository;

    @Mock
    private TenantContext tenantContext;

    @Mock
    private AuditService auditService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RouteService routeService;

    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = new Organization();
        organization.setId(10L);
        organization.setName("Acme Corp");
        organization.setSlug("acme");
        organization.setStatus(OrganizationStatus.ACTIVE);
    }

    private RouteRequest createValidRequest() {
        RouteRequest request = new RouteRequest();
        request.setRouteId("orders-api");
        request.setName("Orders API");
        request.setPathPattern("/acme/orders/**");
        request.setTargetBaseUrl("https://orders.internal.acme.com");
        request.setTargetPathPrefix("/api/v1/orders");
        request.setRequestsPerMinute(120);
        request.setResponseTimeoutMs(5000);
        request.setActive(true);
        request.setAllowedMethods(Set.of("GET", "POST"));
        return request;
    }

    private ApiRoute createMockRoute(Long id, Long version) {
        ApiRoute route = new ApiRoute();
        route.setId(id);
        route.setOrganization(organization);
        route.setRouteId("orders-api");
        route.setName("Orders API");
        route.setPathPattern("/acme/orders/**");
        route.setTargetBaseUrl("https://orders.internal.acme.com");
        route.setTargetPathPrefix("/api/v1/orders");
        route.setRequestsPerMinute(120);
        route.setResponseTimeoutMs(5000);
        route.setActive(true);
        route.setConfigurationVersion(version);
        route.setAllowedMethods(Set.of("GET", "POST"));
        route.setCreatedAt(LocalDateTime.now().minusHours(1));
        route.setUpdatedAt(LocalDateTime.now());
        return route;
    }

    @Nested
    @DisplayName("Route Creation and Retrieval")
    class LifecycleTests {

        @Test
        @DisplayName("create() persists route with version 1 and snapshot without idempotency or header rules")
        void create_successful() {
            RouteRequest request = createValidRequest();
            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.existsByOrganizationIdAndRouteId(10L, "orders-api")).thenReturn(false);
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"version\":1}");

            RouteResponse response = routeService.create(request);

            assertThat(response).isNotNull();
            assertThat(response.getRouteId()).isEqualTo("orders-api");
            assertThat(response.getName()).isEqualTo("Orders API");
            assertThat(response.getConfigurationVersion()).isEqualTo(1L);
            assertThat(response.getRequestsPerMinute()).isEqualTo(120);
            assertThat(response.getResponseTimeoutMs()).isEqualTo(5000);
            assertThat(response.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST");

            ArgumentCaptor<ApiRoute> routeCaptor = ArgumentCaptor.forClass(ApiRoute.class);
            verify(apiRouteRepository).save(routeCaptor.capture());
            ApiRoute saved = routeCaptor.getValue();
            assertThat(saved.getOrganization()).isEqualTo(organization);
            assertThat(saved.getConfigurationVersion()).isEqualTo(1L);

            verify(versionRepository).save(any(RouteConfigurationVersion.class));
            verify(auditService).record(eq(organization), eq("CREATE_ROUTE"), eq("API_ROUTE"),
                    eq("orders-api"), anyLong(), anyString());
        }

        @Test
        @DisplayName("findById() returns route owned by the current tenant")
        void findById_successful() {
            ApiRoute route = createMockRoute(1L, 1L);
            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.findByIdAndOrganizationId(1L, 10L)).thenReturn(Optional.of(route));

            RouteResponse response = routeService.findById(1L);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getRouteId()).isEqualTo("orders-api");
            assertThat(response.getOrganizationId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("findAllForTenant() returns all routes for the current tenant")
        void findAllForTenant_successful() {
            ApiRoute route = createMockRoute(1L, 1L);
            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.findAllByOrganizationId(10L)).thenReturn(List.of(route));

            List<RouteResponse> responses = routeService.findAllForTenant();

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getRouteId()).isEqualTo("orders-api");
        }

        @Test
        @DisplayName("update() increments version and updates route attributes")
        void update_successful() {
            ApiRoute existing = createMockRoute(1L, 1L);
            RouteRequest updateReq = createValidRequest();
            updateReq.setName("Orders API Updated");
            updateReq.setRequestsPerMinute(200);

            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.findByIdAndOrganizationId(1L, 10L)).thenReturn(Optional.of(existing));
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"version\":2}");

            RouteResponse response = routeService.update(1L, updateReq);

            assertThat(response.getName()).isEqualTo("Orders API Updated");
            assertThat(response.getConfigurationVersion()).isEqualTo(2L);
            assertThat(response.getRequestsPerMinute()).isEqualTo(200);

            verify(versionRepository).save(any(RouteConfigurationVersion.class));
        }

        @Test
        @DisplayName("changeStatus() toggles route active flag and increments version")
        void changeStatus_successful() {
            ApiRoute existing = createMockRoute(1L, 1L);
            existing.setActive(true);

            RouteStatusRequest statusReq = new RouteStatusRequest();
            statusReq.setActive(false);

            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.findByIdAndOrganizationId(1L, 10L)).thenReturn(Optional.of(existing));
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"version\":2}");

            RouteResponse response = routeService.changeStatus(1L, statusReq);

            assertThat(response.isActive()).isFalse();
            assertThat(response.getConfigurationVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("delete() removes route, saves final version, and audits")
        void delete_successful() {
            ApiRoute existing = createMockRoute(1L, 1L);
            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.findByIdAndOrganizationId(1L, 10L)).thenReturn(Optional.of(existing));

            routeService.delete(1L);

            verify(apiRouteRepository).delete(existing);
            verify(auditService).record(eq(organization), eq("DELETE_ROUTE"), eq("API_ROUTE"),
                    eq("orders-api"), anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("Validation Rules")
    class ValidationTests {

        @Test
        @DisplayName("Path pattern must start with /{slug}/ prefix")
        void pathPattern_missingTenantPrefix_throwsBadRequest() {
            RouteRequest request = createValidRequest();
            request.setPathPattern("/other-tenant/orders/**");
            when(tenantContext.getOrganization()).thenReturn(organization);

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(apiEx.getMessage()).contains("pathPattern must start with the tenant prefix /acme/");
                    });
        }

        @Test
        @DisplayName("Target path prefix must start with /")
        void targetPathPrefix_missingLeadingSlash_throwsBadRequest() {
            RouteRequest request = createValidRequest();
            request.setTargetPathPrefix("orders/v1");
            when(tenantContext.getOrganization()).thenReturn(organization);

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(apiEx.getMessage()).contains("targetPathPrefix must start with /");
                    });
        }

        @Test
        @DisplayName("Target base URL must use http or https scheme")
        void targetBaseUrl_invalidScheme_throwsBadRequest() {
            RouteRequest request = createValidRequest();
            request.setTargetBaseUrl("ftp://orders.internal.acme.com");
            when(tenantContext.getOrganization()).thenReturn(organization);

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(apiEx.getMessage()).contains("targetBaseUrl must be a valid HTTP or HTTPS URL");
                    });
        }

        @Test
        @DisplayName("Allowed methods are normalized to uppercase")
        void allowedMethods_normalizedToUppercase() {
            RouteRequest request = createValidRequest();
            request.setAllowedMethods(Set.of("get", "post"));
            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.existsByOrganizationIdAndRouteId(10L, "orders-api")).thenReturn(false);
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"version\":1}");

            RouteResponse response = routeService.create(request);

            assertThat(response.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST");
        }

        @Test
        @DisplayName("Duplicate route ID in the same tenant throws CONFLICT")
        void routeId_duplicate_throwsConflict() {
            RouteRequest request = createValidRequest();
            when(tenantContext.getOrganization()).thenReturn(organization);
            when(apiRouteRepository.existsByOrganizationIdAndRouteId(10L, "orders-api")).thenReturn(true);

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(apiEx.getMessage()).contains("Route ID already exists for this organization");
                    });
        }
    }
}
