// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.rbac.PermissionInfo;
import com.yugabyte.yw.common.rbac.PermissionInfo.Action;
import com.yugabyte.yw.common.rbac.PermissionInfo.ResourceType;
import com.yugabyte.yw.common.rbac.PermissionUtil;
import com.yugabyte.yw.common.rbac.RoleBindingUtil;
import com.yugabyte.yw.common.rbac.RoleUtil;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.rbac.ResourcePermissionData;
import com.yugabyte.yw.forms.rbac.RoleBindingFormData;
import com.yugabyte.yw.forms.rbac.RoleFormData;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.rbac.Role;
import com.yugabyte.yw.models.rbac.Role.RoleType;
import com.yugabyte.yw.models.rbac.RoleBinding;
import com.yugabyte.yw.models.rbac.RoleBinding.RoleBindingType;
import com.yugabyte.yw.rbac.annotations.AuthzPath;
import com.yugabyte.yw.rbac.annotations.PermissionAttribute;
import com.yugabyte.yw.rbac.annotations.RequiredPermissionOnResource;
import com.yugabyte.yw.rbac.annotations.Resource;
import com.yugabyte.yw.rbac.enums.SourceType;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

@Api(
    value = "RBAC management",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
@Slf4j
public class RBACController extends AuthenticatedController {

  private final PermissionUtil permissionUtil;
  private final RoleUtil roleUtil;
  private final RoleBindingUtil roleBindingUtil;

  @Inject
  public RBACController(
      PermissionUtil permissionUtil, RoleUtil roleUtil, RoleBindingUtil roleBindingUtil) {
    this.permissionUtil = permissionUtil;
    this.roleUtil = roleUtil;
    this.roleBindingUtil = roleBindingUtil;
  }

  /**
   * List all the permissions info for each resource type. Optionally can be filtered with a query
   * param 'resourceType' to get specific resource permissions.
   *
   * @param customerUUID
   * @param resourceType
   * @return list of all permissions info
   */
  @ApiOperation(
      value = "List all the permissions available",
      nickname = "listPermissions",
      response = PermissionInfo.class,
      responseContainer = "List")
  @AuthzPath
  public Result listPermissions(
      UUID customerUUID,
      @ApiParam(value = "Optional resource type to filter permission list") String resourceType) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    List<PermissionInfo> permissionInfoList = Collections.emptyList();
    ResourceType resourceTypeEnum = EnumUtils.getEnumIgnoreCase(ResourceType.class, resourceType);
    if (resourceTypeEnum != null) {
      permissionInfoList = permissionUtil.getAllPermissionInfo(resourceTypeEnum);
    } else {
      permissionInfoList = permissionUtil.getAllPermissionInfo();
    }
    return PlatformResults.withData(permissionInfoList);
  }

  /**
   * Get the information of a single Role with its UUID.
   *
   * @param customerUUID
   * @param roleUUID
   * @return the role information
   */
  @ApiOperation(value = "Get a role's information", nickname = "getRole", response = Role.class)
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.ROLE, action = Action.READ),
        resourceLocation = @Resource(path = "role", sourceType = SourceType.ENDPOINT))
  })
  public Result getRole(UUID customerUUID, UUID roleUUID) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);
    Role role = Role.getOrBadRequest(customerUUID, roleUUID);
    return PlatformResults.withData(role);
  }

  /**
   * List the information of all roles available. Optionally can be filtered with a query param
   * 'roleType' of Custom or System.
   *
   * @param customerUUID
   * @param roleType
   * @return the list of roles and their information
   */
  @ApiOperation(
      value = "List all the roles available",
      nickname = "listRoles",
      response = Role.class,
      responseContainer = "List")
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.ROLE, action = Action.READ),
        resourceLocation = @Resource(path = "role", sourceType = SourceType.ENDPOINT))
  })
  public Result listRoles(
      UUID customerUUID,
      @ApiParam(value = "Optional role type to filter roles list") String roleType) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    // Get all roles for the customer if 'roleType' is not a valid case insensitive enum
    List<Role> roleList = Collections.emptyList();
    RoleType roleTypeEnum = EnumUtils.getEnumIgnoreCase(RoleType.class, roleType);
    if (roleTypeEnum != null) {
      roleList = Role.getAll(customerUUID, roleTypeEnum);
    } else {
      roleList = Role.getAll(customerUUID);
    }
    return PlatformResults.withData(roleList);
  }

  /**
   * Create a custom role with a given name and a given set of permissions.
   *
   * @param customerUUID
   * @param request
   * @return the info of the role created
   */
  @ApiOperation(value = "Create a custom role", nickname = "createRole", response = Role.class)
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.ROLE, action = Action.CREATE),
        resourceLocation = @Resource(path = "role", sourceType = SourceType.ENDPOINT))
  })
  public Result createRole(UUID customerUUID, Http.Request request) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    // Parse request body.
    JsonNode requestBody = request.body().asJson();
    RoleFormData roleFormData =
        formFactory.getFormDataOrBadRequest(requestBody, RoleFormData.class);

    // Ensure that the given role name is not blank.
    if (roleFormData.name == null || roleFormData.name.isBlank()) {
      String errorMsg = "Role name cannot be blank: " + roleFormData.name;
      log.error(errorMsg);
      throw new PlatformServiceException(BAD_REQUEST, errorMsg);
    }

    // Check if role with given name for that customer already exists.
    if (Role.get(customerUUID, roleFormData.name) != null) {
      String errorMsg = "Role with given name already exists: " + roleFormData.name;
      log.error(errorMsg);
      throw new PlatformServiceException(BAD_REQUEST, errorMsg);
    }

    // Ensure that the given permission list is not empty.
    if (roleFormData.permissionList == null || roleFormData.permissionList.isEmpty()) {
      String errorMsg = "Permission list cannot be empty.";
      log.error(errorMsg);
      throw new PlatformServiceException(BAD_REQUEST, errorMsg);
    }

    // Create a custom role now.
    Role role =
        roleUtil.createRole(
            customerUUID,
            roleFormData.name,
            roleFormData.description,
            RoleType.Custom,
            roleFormData.permissionList);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.Role,
            role.getRoleUUID().toString(),
            Audit.ActionType.Create,
            Json.toJson(roleFormData));
    return PlatformResults.withData(role);
  }

  /**
   * Edit the set of permissions of a given custom role.
   *
   * @param customerUUID
   * @param roleUUID
   * @param request
   * @return the info of the edited role
   */
  @ApiOperation(value = "Edit a custom role", nickname = "editRole", response = Role.class)
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.ROLE, action = Action.UPDATE),
        resourceLocation = @Resource(path = "role", sourceType = SourceType.ENDPOINT))
  })
  public Result editRole(UUID customerUUID, UUID roleUUID, Http.Request request) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    // Parse request body.
    JsonNode requestBody = request.body().asJson();
    RoleFormData roleFormData =
        formFactory.getFormDataOrBadRequest(requestBody, RoleFormData.class);

    // Check if role with given UUID exists for that customer.
    Role role = Role.get(customerUUID, roleUUID);
    if (role == null) {
      String errorMsg =
          String.format(
              "Role with UUID '%s' doesn't exist for customer '%s'.", roleUUID, customerUUID);
      log.error(errorMsg);
      throw new PlatformServiceException(NOT_FOUND, errorMsg);
    }

    // Ensure we are not modifying system defined roles.
    if (RoleType.System.equals(role.getRoleType())) {
      String errorMsg = "Cannot modify System Role with given UUID: " + roleUUID;
      log.error(errorMsg);
      throw new PlatformServiceException(BAD_REQUEST, errorMsg);
    }

    if (roleFormData.name != null) {
      log.warn("Editing the role name is not supported, skipping this operation.");
    }

    if (roleFormData.permissionList == null) {
      log.warn("Permission list not given, using the previous permission list itself.");
      roleFormData.permissionList =
          Role.get(customerUUID, roleUUID).getPermissionDetails().getPermissionList();
    } else if (roleFormData.permissionList.isEmpty()) {
      String errorMsg = "Given permission list cannot be empty.";
      log.error(errorMsg);
      throw new PlatformServiceException(BAD_REQUEST, errorMsg);
    }

    // Edit the custom role with given description and permissions.
    // Description and set of permissions are the only editable fields in a role.
    role =
        roleUtil.editRole(
            customerUUID, roleUUID, roleFormData.description, roleFormData.permissionList);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.Role,
            role.getRoleUUID().toString(),
            Audit.ActionType.Edit,
            Json.toJson(roleFormData));
    return PlatformResults.withData(role);
  }

  /**
   * Delete a custom role with a given role UUID.
   *
   * @param customerUUID
   * @param roleUUID
   * @param request
   * @return a success message if deleted properly.
   */
  @ApiOperation(
      value = "Delete a custom role",
      nickname = "deleteRole",
      response = YBPSuccess.class)
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.ROLE, action = Action.DELETE),
        resourceLocation = @Resource(path = "role", sourceType = SourceType.ENDPOINT))
  })
  public Result deleteRole(UUID customerUUID, UUID roleUUID, Http.Request request) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    // Check if role with given UUID exists.
    Role role = Role.get(customerUUID, roleUUID);
    if (role == null) {
      String errorMsg =
          String.format(
              "Role with UUID '%s' doesn't exist for customer '%s'.", roleUUID, customerUUID);
      log.error(errorMsg);
      throw new PlatformServiceException(NOT_FOUND, errorMsg);
    }

    // Ensure we are not deleting system defined roles.
    if (RoleType.System.equals(role.getRoleType())) {
      String errorMsg = "Cannot delete System Role with given UUID: " + roleUUID;
      log.error(errorMsg);
      throw new PlatformServiceException(BAD_REQUEST, errorMsg);
    }

    // Ensure we don't delete role if role_binding exists.
    Set<String> usersHavingRoleBindingsWithRole =
        RoleBinding.getAllWithRole(roleUUID).stream()
            .map(rb -> rb.getUser().getEmail())
            .collect(Collectors.toSet());
    if (!usersHavingRoleBindingsWithRole.isEmpty()) {
      String errorMsg =
          String.format(
              "Cannot delete Role with name: '%s', "
                  + "since there are role bindings associated on users '%s'.",
              role.getName(), usersHavingRoleBindingsWithRole);
      log.error(errorMsg);
      throw new PlatformServiceException(CONFLICT, errorMsg);
    }

    // Delete the custom role.
    roleUtil.deleteRole(customerUUID, roleUUID);
    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.Role, roleUUID.toString(), Audit.ActionType.Delete);
    return YBPSuccess.withMessage(
        String.format(
            "Successfully deleted role with UUID '%s' for customer '%s'", roleUUID, customerUUID));
  }

  @ApiOperation(
      value = "Get all the role bindings available",
      nickname = "getRoleBindings",
      response = RoleBinding.class,
      responseContainer = "Map")
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.USER, action = Action.READ),
        resourceLocation = @Resource(path = "userUUID", sourceType = SourceType.ENDPOINT))
  })
  public Result listRoleBinding(
      UUID customerUUID,
      @ApiParam(value = "Optional user UUID to filter role binding map") UUID userUUID) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    Map<UUID, List<RoleBinding>> roleBindingMap = new HashMap<>();
    if (userUUID != null) {
      // Get the role bindings for the given user if 'userUUID' is not null.
      Users user = Users.getOrBadRequest(customerUUID, userUUID);
      roleBindingMap.put(user.getUuid(), RoleBinding.getAll(user.getUuid()));
    } else {
      // Get all the users for the given customer.
      // Merge all the role bindings for users of the customer.
      List<Users> usersInCustomer = Users.getAll(customerUUID);
      for (Users user : usersInCustomer) {
        roleBindingMap.put(user.getUuid(), RoleBinding.getAll(user.getUuid()));
      }
    }
    return PlatformResults.withData(roleBindingMap);
  }

  @ApiOperation(
      value = "Set the role bindings of a user",
      nickname = "setRoleBinding",
      response = RoleBinding.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "RoleBindingFormData",
          value = "set role bindings form data",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.rbac.RoleBindingFormData",
          required = true))
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(
                resourceType = ResourceType.USER,
                action = Action.UPDATE_ROLE_BINDINGS),
        resourceLocation = @Resource(path = "role_binding", sourceType = SourceType.ENDPOINT))
  })
  public Result setRoleBindings(UUID customerUUID, UUID userUUID, Http.Request request) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    // Check if user UUID exists within the above customer
    Users.getOrBadRequest(customerUUID, userUUID);

    // Parse request body.
    JsonNode requestBody = request.body().asJson();
    RoleBindingFormData roleBindingFormData =
        formFactory.getFormDataOrBadRequest(requestBody, RoleBindingFormData.class);

    // Validate the roles and resource group definitions.
    roleBindingUtil.validateRoles(userUUID, roleBindingFormData.getRoleResourceDefinitions());
    roleBindingUtil.validateResourceGroups(
        customerUUID, roleBindingFormData.getRoleResourceDefinitions());

    // Delete all existing user role bindings and create new given role bindings.
    List<RoleBinding> createdRoleBindings =
        roleBindingUtil.setUserRoleBindings(
            userUUID, roleBindingFormData.getRoleResourceDefinitions(), RoleBindingType.Custom);

    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.RoleBinding,
            userUUID.toString(),
            Audit.ActionType.Edit,
            Json.toJson(roleBindingFormData));
    return PlatformResults.withData(createdRoleBindings);
  }

  @ApiOperation(
      value = "UI_ONLY",
      nickname = "getUserResourcePermissions",
      response = ResourcePermissionData.class,
      responseContainer = "Set",
      hidden = true,
      notes = "Get all the resource permissions that a user has.")
  @AuthzPath({
    @RequiredPermissionOnResource(
        requiredPermission =
            @PermissionAttribute(resourceType = ResourceType.USER, action = Action.READ),
        resourceLocation = @Resource(path = "user", sourceType = SourceType.ENDPOINT))
  })
  public Result getUserResourcePermissions(
      UUID customerUUID,
      UUID userUUID,
      @ApiParam(value = "Optional resource type to filter resource permission list")
          String resourceType) {
    // Check if customer exists.
    Customer.getOrBadRequest(customerUUID);

    // Check if user UUID exists within the above customer
    Users.getOrBadRequest(customerUUID, userUUID);

    // Filters for the given resource type if given, else returns all permissions for user.
    ResourceType resourceTypeEnum = EnumUtils.getEnumIgnoreCase(ResourceType.class, resourceType);
    Set<ResourcePermissionData> userResourcePermissions =
        roleBindingUtil.getUserResourcePermissions(customerUUID, userUUID, resourceTypeEnum);
    return PlatformResults.withData(userResourcePermissions);
  }
}