package in.coderarmy.gatekeeper.management.controller;

import in.coderarmy.gatekeeper.management.config.SecurityConfig;
import in.coderarmy.gatekeeper.management.dto.responseDto.RouteResponse;
import in.coderarmy.gatekeeper.management.service.AuthenticationService;
import in.coderarmy.gatekeeper.management.service.HealthService;
import in.coderarmy.gatekeeper.management.service.OrganizationService;
import in.coderarmy.gatekeeper.management.service.RouteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        GatewayConfigurationController.class,
        PlatformOrganizationController.class,
        TenantRouteController.class,
        AuthenticationController.class
})
@Import(SecurityConfig.class)
class ApiManagementSecurityAndGatewayTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteService routeService;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private AuthenticationService authenticationService;

    @Test
    @DisplayName("GET /api/gateway/routes without token returns 401 Unauthorized")
    void gatewayRoutes_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/gateway/routes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/gateway/routes with TENANT_ADMIN token returns 403 Forbidden")
    void gatewayRoutes_tenantAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/gateway/routes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/gateway/routes with PLATFORM_ADMIN token returns 403 Forbidden")
    void gatewayRoutes_platformAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/gateway/routes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/gateway/routes with GATEWAY_SERVICE token returns 200 OK")
    void gatewayRoutes_gatewayService_returns200() throws Exception {
        when(routeService.findAllActiveForGateway()).thenReturn(List.of());

        mockMvc.perform(get("/api/gateway/routes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GATEWAY_SERVICE"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/platform/organizations with GATEWAY_SERVICE returns 403 Forbidden")
    void platformEndpoints_gatewayService_returns403() throws Exception {
        mockMvc.perform(get("/api/platform/organizations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GATEWAY_SERVICE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/platform/organizations with PLATFORM_ADMIN returns 200 OK")
    void platformEndpoints_platformAdmin_returns200() throws Exception {
        when(organizationService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/platform/organizations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/tenant/routes with PLATFORM_ADMIN returns 403 Forbidden")
    void tenantEndpoints_platformAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/tenant/routes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/tenant/routes with TENANT_ADMIN returns 200 OK")
    void tenantEndpoints_tenantAdmin_returns200() throws Exception {
        when(routeService.findAllForTenant()).thenReturn(List.of());

        mockMvc.perform(get("/api/tenant/routes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))))
                .andExpect(status().isOk());
    }
}
