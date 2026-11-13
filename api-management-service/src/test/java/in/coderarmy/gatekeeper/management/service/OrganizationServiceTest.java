package in.coderarmy.gatekeeper.management.service;

import in.coderarmy.gatekeeper.management.dto.requestDto.OrganizationRequest;
import in.coderarmy.gatekeeper.management.dto.requestDto.OrganizationStatusRequest;
import in.coderarmy.gatekeeper.management.dto.requestDto.TenantAdminRequest;
import in.coderarmy.gatekeeper.management.dto.responseDto.OrganizationResponse;
import in.coderarmy.gatekeeper.management.dto.responseDto.TenantAdminResponse;
import in.coderarmy.gatekeeper.management.entity.ManagementUser;
import in.coderarmy.gatekeeper.management.entity.Organization;
import in.coderarmy.gatekeeper.management.entity.OrganizationStatus;
import in.coderarmy.gatekeeper.management.entity.Role;
import in.coderarmy.gatekeeper.management.exception.ApiException;
import in.coderarmy.gatekeeper.management.repository.ManagementUserRepository;
import in.coderarmy.gatekeeper.management.repository.OrganizationRepository;
import in.coderarmy.gatekeeper.management.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ManagementUserRepository managementUserRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TenantContext tenantContext;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private OrganizationService organizationService;

    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = new Organization();
        organization.setId(1L);
        organization.setName("Acme Corp");
        organization.setSlug("acme");
        organization.setStatus(OrganizationStatus.PENDING);
    }

    @Test
    @DisplayName("create() provisions organization in PENDING status and records audit")
    void create_provisionsOrganizationSuccessfully() {
        OrganizationRequest request = new OrganizationRequest();
        request.setName("Acme Corp");
        request.setSlug("acme");

        when(organizationRepository.existsByNameIgnoreCase("Acme Corp")).thenReturn(false);
        when(organizationRepository.existsBySlugIgnoreCase("acme")).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization o = invocation.getArgument(0);
            o.setId(10L);
            return o;
        });

        OrganizationResponse response = organizationService.create(request);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getName()).isEqualTo("Acme Corp");
        assertThat(response.getSlug()).isEqualTo("acme");
        assertThat(response.getStatus()).isEqualTo(OrganizationStatus.PENDING);

        verify(auditService).record(any(Organization.class), eq("CREATE_ORGANIZATION"),
                eq("ORGANIZATION"), eq("10"), anyLong(), anyString());
    }

    @Test
    @DisplayName("changeStatus() transitions organization status and records audit")
    void changeStatus_updatesStatusSuccessfully() {
        when(organizationRepository.findByIdAndStatusNot(1L, OrganizationStatus.DELETED))
                .thenReturn(Optional.of(organization));

        OrganizationStatusRequest request = new OrganizationStatusRequest();
        request.setStatus(OrganizationStatus.ACTIVE);

        OrganizationResponse response = organizationService.changeStatus(1L, request);

        assertThat(response.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
        verify(auditService).record(eq(organization), eq("CHANGE_ORGANIZATION_STATUS"),
                eq("ORGANIZATION"), eq("1"), anyLong(), anyString());
    }

    @Test
    @DisplayName("delete() performs soft-delete and records audit")
    void delete_softDeletesOrganization() {
        when(organizationRepository.findByIdAndStatusNot(1L, OrganizationStatus.DELETED))
                .thenReturn(Optional.of(organization));

        organizationService.delete(1L);

        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.DELETED);
        assertThat(organization.getDeletedAt()).isNotNull();
        verify(auditService).record(eq(organization), eq("DELETE_ORGANIZATION"),
                eq("ORGANIZATION"), eq("1"), anyLong(), anyString());
    }

    @Test
    @DisplayName("createTenantAdmin() provisions tenant administrator with TENANT_ADMIN role")
    void createTenantAdmin_provisionsAdminSuccessfully() {
        when(organizationRepository.findByIdAndStatusNot(1L, OrganizationStatus.DELETED))
                .thenReturn(Optional.of(organization));
        when(managementUserRepository.findByOrganizationId(1L)).thenReturn(Optional.empty());
        when(managementUserRepository.existsByUsername("acme-admin")).thenReturn(false);

        Role tenantRole = new Role();
        tenantRole.setId(2L);
        tenantRole.setName("TENANT_ADMIN");
        when(roleRepository.findByName("TENANT_ADMIN")).thenReturn(Optional.of(tenantRole));
        when(passwordEncoder.encode("securePass123")).thenReturn("hashedPassword");

        TenantAdminRequest request = new TenantAdminRequest();
        request.setUsername("acme-admin");
        request.setPassword("securePass123");
        request.setEnabled(true);

        when(managementUserRepository.save(any(ManagementUser.class))).thenAnswer(invocation -> {
            ManagementUser user = invocation.getArgument(0);
            user.setId(100L);
            return user;
        });

        TenantAdminResponse response = organizationService.createTenantAdmin(1L, request);

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getUsername()).isEqualTo("acme-admin");
        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getOrganizationId()).isEqualTo(1L);

        verify(auditService).record(eq(organization), eq("CREATE_TENANT_ADMIN"),
                eq("MANAGEMENT_USER"), eq("100"), anyLong(), anyString());
    }

    @Test
    @DisplayName("createTenantAdmin() rejects duplicate admin for same organization")
    void createTenantAdmin_rejectsDuplicateAdmin() {
        when(organizationRepository.findByIdAndStatusNot(1L, OrganizationStatus.DELETED))
                .thenReturn(Optional.of(organization));
        when(managementUserRepository.findByOrganizationId(1L)).thenReturn(Optional.of(new ManagementUser()));

        TenantAdminRequest request = new TenantAdminRequest();
        request.setUsername("acme-admin-2");
        request.setPassword("pass");

        assertThatThrownBy(() -> organizationService.createTenantAdmin(1L, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already has a tenant administrator");
    }
}
