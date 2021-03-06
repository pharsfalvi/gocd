/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.server.security.providers;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.PluginRoleConfig;
import com.thoughtworks.go.config.SecurityAuthConfig;
import com.thoughtworks.go.plugin.access.authentication.models.User;
import com.thoughtworks.go.plugin.access.authorization.AuthorizationExtension;
import com.thoughtworks.go.plugin.access.authorization.models.AuthenticationResponse;
import com.thoughtworks.go.server.security.AuthorityGranter;
import com.thoughtworks.go.server.security.tokens.PreAuthenticatedAuthenticationToken;
import com.thoughtworks.go.server.security.userdetail.GoUserPrinciple;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.PluginRoleService;
import com.thoughtworks.go.server.service.UserService;
import org.springframework.security.Authentication;
import org.springframework.security.AuthenticationException;
import org.springframework.security.BadCredentialsException;
import org.springframework.security.providers.AuthenticationProvider;
import org.springframework.security.userdetails.UserDetails;

import java.util.List;

import static org.apache.commons.lang.StringUtils.isNotBlank;

public class PreAuthenticatedAuthenticationProvider implements AuthenticationProvider {
    private final AuthorizationExtension authorizationExtension;
    private final PluginRoleService pluginRoleService;
    private final UserService userService;
    private final AuthorityGranter authorityGranter;
    private GoConfigService configService;

    public PreAuthenticatedAuthenticationProvider(AuthorizationExtension authorizationExtension, PluginRoleService pluginRoleService,
                                                  UserService userService, AuthorityGranter authorityGranter, GoConfigService configService) {
        this.authorizationExtension = authorizationExtension;
        this.pluginRoleService = pluginRoleService;
        this.userService = userService;
        this.authorityGranter = authorityGranter;
        this.configService = configService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!supports(authentication.getClass())) {
            return null;
        }

        if (authentication.getCredentials() == null) {
            throw new BadCredentialsException("No pre-authenticated credentials found in request.");
        }

        PreAuthenticatedAuthenticationToken preAuthToken = (PreAuthenticatedAuthenticationToken) authentication;

        return doAuthenticate(preAuthToken);
    }

    private Authentication doAuthenticate(PreAuthenticatedAuthenticationToken preAuthToken) {
        String pluginId = preAuthToken.getPluginId();

        AuthenticationResponse response = authorizationExtension.authenticateUser(pluginId, preAuthToken.getCredentials(),
                authConfigs(pluginId), pluginRoleConfigs(pluginId));

        if(authenticationFailed(response)) {
            return null;
        }

        UserDetails userDetails = getUserDetails(response.getUser());

        userService.addUserIfDoesNotExist(toDomainUser(response.getUser()));

        assignRoles(pluginId, userDetails.getUsername(), response.getRoles());

        PreAuthenticatedAuthenticationToken result =
                new PreAuthenticatedAuthenticationToken(userDetails, preAuthToken.getCredentials(), pluginId, userDetails.getAuthorities());

        result.setAuthenticated(true);

        return result;
    }

    @Override
    public boolean supports(Class authentication) {
        return PreAuthenticatedAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private boolean authenticationFailed(AuthenticationResponse response) {
        return response.getUser() == null;
    }

    private List<PluginRoleConfig> pluginRoleConfigs(String pluginId) {
        return configService.security().getPluginRoles(pluginId);
    }

    private List<SecurityAuthConfig> authConfigs(String pluginId) {
        return configService.security().securityAuthConfigs().findByPluginId(pluginId);
    }

    private void assignRoles(String pluginId, String username, List<String> roles) {
        pluginRoleService.updatePluginRoles(pluginId, username, CaseInsensitiveString.caseInsensitiveStrings(roles));
    }

    private UserDetails getUserDetails(User user) {
        user = ensureDisplayNamePresent(user);

        return new GoUserPrinciple(user.getUsername(), user.getDisplayName(), "", true, true, true, true, authorityGranter.authorities(user.getUsername()));
    }

    private com.thoughtworks.go.domain.User toDomainUser(User user) {
        return new com.thoughtworks.go.domain.User(user.getUsername(), user.getDisplayName(), user.getEmailId());
    }

    private User ensureDisplayNamePresent(User user) {
        if (user == null) {
            return null;
        }

        if (isNotBlank(user.getDisplayName())) {
            return user;
        }

        return new User(user.getUsername(), user.getUsername(), user.getEmailId());
    }
}