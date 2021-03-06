/**
 * Licensed to Apereo under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership. Apereo
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the License at the
 * following location:
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apereo.portal.portlets.groupadmin;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apereo.portal.EntityIdentifier;
import org.apereo.portal.groups.IEntityGroup;
import org.apereo.portal.groups.IGroupMember;
import org.apereo.portal.layout.dlm.remoting.IGroupListHelper;
import org.apereo.portal.layout.dlm.remoting.JsonEntityBean;
import org.apereo.portal.portlets.groupselector.EntityEnum;
import org.apereo.portal.security.IAuthorizationPrincipal;
import org.apereo.portal.security.IPermission;
import org.apereo.portal.security.IPerson;
import org.apereo.portal.security.RuntimeAuthorizationException;
import org.apereo.portal.services.AuthorizationServiceFacade;
import org.apereo.portal.services.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * GroupAdministrationHelper provides helper groups for the groups administration webflows. These
 * methods include convenience methods for populating and editing form objects, as well as saving
 * information supplied to a group form.
 */
@Service
public class GroupAdministrationHelper {

    protected final Log log = LogFactory.getLog(getClass());

    private IGroupListHelper groupListHelper;

    @Autowired(required = true)
    public void setGroupListHelper(IGroupListHelper groupListHelper) {
        this.groupListHelper = groupListHelper;
    }

    /**
     * Construct a group form for the group with the specified key.
     *
     * @param key
     * @param entityEnum
     * @return
     */
    public GroupForm getGroupForm(String key) {

        log.debug("Initializing group form for group key " + key);

        // find the current version of this group entity
        IEntityGroup group = GroupService.findGroup(key);

        // update the group form with the existing group's main information
        GroupForm form = new GroupForm();
        form.setKey(key);
        form.setName(group.getName());
        form.setDescription(group.getDescription());
        form.setCreatorId(group.getCreatorID());
        form.setType(groupListHelper.getEntityType(group).toString());

        // add child groups to our group form bean
        for (IGroupMember child : group.getChildren()) {
            JsonEntityBean childBean = groupListHelper.getEntity(child);
            form.addMember(childBean);
        }

        return form;
    }

    /**
     * Delete a group from the group store
     *
     * @param key key of the group to be deleted
     * @param user performing the delete operation
     */
    public void deleteGroup(String key, IPerson deleter) {

        if (!canDeleteGroup(deleter, key)) {
            throw new RuntimeAuthorizationException(
                    deleter, IPermission.DELETE_GROUP_ACTIVITY, key);
        }

        log.info("Deleting group with key " + key);

        // find the current version of this group entity
        IEntityGroup group = GroupService.findGroup(key);

        // remove this group from the membership list of any current parent
        // groups
        for (IEntityGroup parent : group.getParentGroups()) {
            parent.removeChild(group);
            parent.updateMembers();
        }

        // delete the group
        group.delete();
    }

    /**
     * Update the title and description of an existing group in the group store.
     *
     * @param groupForm Form representing the new group configuration
     * @param updater Updating user
     */
    public void updateGroupDetails(GroupForm groupForm, IPerson updater) {

        if (!canEditGroup(updater, groupForm.getKey())) {
            throw new RuntimeAuthorizationException(
                    updater, IPermission.EDIT_GROUP_ACTIVITY, groupForm.getKey());
        }

        if (log.isDebugEnabled()) {
            log.debug("Updating group for group form [" + groupForm.toString() + "]");
        }

        // find the current version of this group entity
        IEntityGroup group = GroupService.findGroup(groupForm.getKey());
        group.setName(groupForm.getName());
        group.setDescription(groupForm.getDescription());

        // save the group, updating both its basic information and group
        // membership
        group.update();
    }

    /**
     * Update the members of an existing group in the group store.
     *
     * @param groupForm Form representing the new group configuration
     * @param updater Updating user
     */
    public void updateGroupMembers(GroupForm groupForm, IPerson updater) {

        if (!canEditGroup(updater, groupForm.getKey())) {
            throw new RuntimeAuthorizationException(
                    updater, IPermission.EDIT_GROUP_ACTIVITY, groupForm.getKey());
        }

        if (log.isDebugEnabled()) {
            log.debug("Updating group members for group form [" + groupForm.toString() + "]");
        }

        // find the current version of this group entity
        IEntityGroup group = GroupService.findGroup(groupForm.getKey());

        // clear the current group membership list
        for (IGroupMember child : group.getChildren()) {
            group.removeChild(child);
        }

        // add all the group membership information from the group form
        // to the group
        for (JsonEntityBean child : groupForm.getMembers()) {
            EntityEnum type = EntityEnum.getEntityEnum(child.getEntityTypeAsString());
            if (type.isGroup()) {
                IEntityGroup member = GroupService.findGroup(child.getId());
                group.addChild(member);
            } else {
                IGroupMember member = GroupService.getGroupMember(child.getId(), type.getClazz());
                group.addChild(member);
            }
        }

        // save the group, updating both its basic information and group
        // membership
        group.updateMembers();
    }

    /**
     * Create a new group under the specified parent. The new group will automatically be added to
     * the parent group.
     *
     * @param groupForm form object representing the new group
     * @param parent parent group for this new group
     * @param creator the uPortal user creating the new group
     */
    public void createGroup(GroupForm groupForm, JsonEntityBean parent, IPerson creator) {

        if (!canCreateMemberGroup(creator, parent.getId())) {
            throw new RuntimeAuthorizationException(
                    creator, IPermission.CREATE_GROUP_ACTIVITY, groupForm.getKey());
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "Creating new group for group form ["
                            + groupForm.toString()
                            + "] and parent ["
                            + parent.toString()
                            + "]");
        }

        // get the entity type of the parent group
        EntityEnum type = EntityEnum.getEntityEnum(groupForm.getType());

        // create a new group with the parent's entity type
        IEntityGroup group = GroupService.newGroup(type.getClazz());

        // find the current version of this group entity
        group.setCreatorID(creator.getUserName());
        group.setName(groupForm.getName());
        group.setDescription(groupForm.getDescription());

        // add all the group membership information from the group form
        // to the group
        for (JsonEntityBean child : groupForm.getMembers()) {
            EntityEnum childType = EntityEnum.getEntityEnum(child.getEntityTypeAsString());
            if (childType.isGroup()) {
                IEntityGroup member = GroupService.findGroup(child.getId());
                group.addChild(member);
            } else {
                IGroupMember member = GroupService.getGroupMember(child.getId(), type.getClazz());
                group.addChild(member);
            }
        }

        // save the group, updating both its basic information and group membership
        group.update();

        // add this group to the membership list for the specified parent
        IEntityGroup parentGroup = GroupService.findGroup(parent.getId());
        parentGroup.addChild(group);
        parentGroup.updateMembers();
    }

    public boolean canEditGroup(IPerson currentUser, String target) {
        EntityIdentifier ei = currentUser.getEntityIdentifier();
        IAuthorizationPrincipal ap =
                AuthorizationServiceFacade.instance().newPrincipal(ei.getKey(), ei.getType());
        return (ap.hasPermission(
                IPermission.PORTAL_GROUPS, IPermission.EDIT_GROUP_ACTIVITY, target));
    }

    public boolean canDeleteGroup(IPerson currentUser, String target) {
        EntityIdentifier ei = currentUser.getEntityIdentifier();
        IAuthorizationPrincipal ap =
                AuthorizationServiceFacade.instance().newPrincipal(ei.getKey(), ei.getType());
        return (ap.hasPermission(
                IPermission.PORTAL_GROUPS, IPermission.DELETE_GROUP_ACTIVITY, target));
    }

    public boolean canCreateMemberGroup(IPerson currentUser, String target) {
        EntityIdentifier ei = currentUser.getEntityIdentifier();
        IAuthorizationPrincipal ap =
                AuthorizationServiceFacade.instance().newPrincipal(ei.getKey(), ei.getType());
        return (ap.hasPermission(
                IPermission.PORTAL_GROUPS, IPermission.CREATE_GROUP_ACTIVITY, target));
    }

    public boolean canViewGroup(IPerson currentUser, String target) {
        EntityIdentifier ei = currentUser.getEntityIdentifier();
        IAuthorizationPrincipal ap =
                AuthorizationServiceFacade.instance().newPrincipal(ei.getKey(), ei.getType());
        return (ap.hasPermission(
                IPermission.PORTAL_GROUPS, IPermission.VIEW_GROUP_ACTIVITY, target));
    }

    /**
     * Get the authorization principal matching the supplied IPerson.
     *
     * @param person
     * @return
     */
    protected IAuthorizationPrincipal getPrincipalForUser(final IPerson person) {
        final EntityIdentifier ei = person.getEntityIdentifier();
        return AuthorizationServiceFacade.instance().newPrincipal(ei.getKey(), ei.getType());
    }
}
