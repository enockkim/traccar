/*
 * Copyright 2015 - 2023 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api.security;

import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.resource.SessionResource;
import org.traccar.database.StatisticsManager;
import org.traccar.helper.DataConverter;
import org.traccar.model.User;
import org.traccar.storage.StorageException;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

public class SecurityRequestFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityRequestFilter.class);

    public static String[] decodeBasicAuth(String auth) {
        auth = auth.replaceFirst("[B|b]asic ", "");
        byte[] decodedBytes = DataConverter.parseBase64(auth);
        if (decodedBytes != null && decodedBytes.length > 0) {
            return new String(decodedBytes, StandardCharsets.US_ASCII).split(":", 2);
        }
        return null;
    }

    @Context
    private HttpServletRequest request;

    @Context
    private ResourceInfo resourceInfo;

    @Inject
    private LoginService loginService;

    @Inject
    private StatisticsManager statisticsManager;

    @Inject
    private Injector injector;

    @Override
    public void filter(ContainerRequestContext requestContext) {

        if (requestContext.getMethod().equals("OPTIONS")) {
            return;
        }

        SecurityContext securityContext = null;

        try {

            String authHeader = requestContext.getHeaderString("Authorization");
            if (authHeader != null) {

                try {
                    User user;
                    if (authHeader.startsWith("Bearer ")) {
                        user = loginService.login(authHeader.substring(7));
                    } else {
                        String[] auth = decodeBasicAuth(authHeader);
                        user = loginService.login(auth[0], auth[1]);
                    }
                    if (user != null) {
                        statisticsManager.registerRequest(user.getId());
                        securityContext = new UserSecurityContext(new UserPrincipal(user.getId()));
                    }
                } catch (StorageException | GeneralSecurityException | IOException e) {
                    throw new WebApplicationException(e);
                }

            } else if (request.getSession() != null) {

                Long userId = (Long) request.getSession().getAttribute(SessionResource.USER_ID_KEY);
                if (userId != null) {
                    injector.getInstance(PermissionsService.class).getUser(userId).checkDisabled();
                    statisticsManager.registerRequest(userId);
                    securityContext = new UserSecurityContext(new UserPrincipal(userId));
                }

            }

        } catch (SecurityException | StorageException e) {
            LOGGER.warn("Authentication error", e);
        }

        if (securityContext != null) {
            requestContext.setSecurityContext(securityContext);
        } else {
            Method method = resourceInfo.getResourceMethod();
            if (!method.isAnnotationPresent(PermitAll.class)) {
                Response.ResponseBuilder responseBuilder = Response.status(Response.Status.UNAUTHORIZED);
                String accept = request.getHeader("Accept");
                if (accept != null && accept.contains("text/html")) {
                    responseBuilder.header("WWW-Authenticate", "Basic realm=\"api\"");
                }
                throw new WebApplicationException(responseBuilder.build());
            }
        }

    }

}
