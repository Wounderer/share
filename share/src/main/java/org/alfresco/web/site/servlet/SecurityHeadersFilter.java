/*
 * #%L
 * Alfresco Share WAR
 * %%
 * Copyright (C) 2005 - 2016 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.web.site.servlet;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.extensions.config.Config;
import org.springframework.extensions.config.ConfigElement;
import org.springframework.extensions.config.ConfigService;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * A filter adding HTTP response headers to incoming requests to improve security for the webapp.
 *
 * The logic is configurable making it possible to configure which headers that shall be added.
 *
 * @author Erik Winlof
 */
public class SecurityHeadersFilter implements Filter
{
    private static Log logger = LogFactory.getLog(SecurityHeadersFilter.class);

    private ServletContext servletContext = null;

    private Boolean enabled = true;
    private List<Header> headers = new LinkedList<Header>();

    /**
     * Parses the headers config.
     *
     * @param config The filter config
     * @throws javax.servlet.ServletException if the headers filter config is invalid
     */
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        servletContext = config.getServletContext();

        ApplicationContext context = getApplicationContext();

        ConfigService configService = (ConfigService)context.getBean("web.config");

        // Retrieve the remote configuration
        Config securityHeadersConfig = (Config) configService.getConfig("SecurityHeadersPolicy");
        if (securityHeadersConfig == null)
        {
            enabled = false;
            if (logger.isDebugEnabled())
                logger.debug("There is no 'SecurityHeadersPolicy' config, no headers will be added.");
        }
        else
        {
            ConfigElement headersConfig = securityHeadersConfig.getConfigElement("headers");
            if (headersConfig == null)
            {
                enabled = false;
                if (logger.isDebugEnabled())
                    logger.debug("The 'SecurityHeadersPolicy' config had no headers, no headers will be added.");
            }
            else
            {
                List<ConfigElement> headersConfigList = headersConfig.getChildren("header");
                if (headersConfigList == null || headersConfigList.size() == 0)
                {
                    enabled = false;
                    if (logger.isDebugEnabled())
                        logger.debug("The 'SecurityHeadersPolicy' headers config was empty, no headers will be added.");
                }
                else
                {
                    // Get and merge all configs
                    Map<String, Header> allHeaders = new HashMap<String, Header>();
                    for (ConfigElement headerConfig : headersConfigList)
                    {
                        // Name
                        String name = headerConfig.getChildValue("name");
                        Header header;
                        if (allHeaders.containsKey(name))
                        {
                            header = allHeaders.get(name);
                        }
                        else
                        {
                            header = new Header();
                            header.setName(name);
                            allHeaders.put(name, header);
                        }

                        // Vaule
                        ConfigElement valueConfig = headerConfig.getChild("value");
                        if (valueConfig != null)
                        {
                            header.setValue(valueConfig.getValue());
                        }

                        // Enabled
                        ConfigElement enabledConfig = headerConfig.getChild("enabled");
                        if (enabledConfig != null)
                        {
                            String enabled = enabledConfig.getValue();
                            header.setEnabled(enabled == null || enabled.equalsIgnoreCase("true"));
                        }
                    }

                    // Filter out all enabled configs
                    for (Header header: allHeaders.values())
                    {
                        if (header.getEnabled())
                        {
                            headers.add(header);
                        }
                    }
                }
            }
        }
    }


    /**
     * Will add the configured response headers to the response.
     *
     * @param servletRequest The servlet request
     * @param servletResponse The servlet response
     * @param filterChain The filter chain
     * @throws java.io.IOException
     * @throws ServletException
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException
    {
        if (enabled && servletRequest instanceof HttpServletRequest && servletResponse instanceof HttpServletResponse)
        {
            HttpServletResponse response = (HttpServletResponse) servletResponse;
            for (Header header : headers)
            {
                response.setHeader(header.getName(), header.getValue());
            }
            
            // Wrap the response object.
            servletResponse = new CookieCustomisingResponse(response);
        }

        // Proceed as usual
        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy()
    {
    }

    /**
     * Retrieves the root application context
     *
     * @return application context
     */
    private ApplicationContext getApplicationContext()
    {
        return WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
    }

    /**
     * Internal representation of a header.
     */
    private class Header
    {
        private String name;
        private String value;
        private Boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
    
    /**
     * Response wrapper that intercepts addCookie() calls and invokes setHttpOnly()
     * on the cookie.
     */
    private static class CookieCustomisingResponse extends HttpServletResponseWrapper
    {
        public CookieCustomisingResponse(HttpServletResponse response)
        {
            super(response);
        }

        @Override
        public void addCookie(Cookie cookie)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("addCookie(<"+cookie.getName()+">) called");
            }
            if (httpOnlyRequired(cookie))
            {
                setHttpOnly(cookie);
            }
            super.addCookie(cookie);
        }

        /**
         * Is the httpOnly setting required for this cookie?
         * 
         * @param cookie
         * @return true if httpOnly must be set for this cookie.
         */
        private boolean httpOnlyRequired(Cookie cookie)
        {
            return !cookie.getName().equals("Alfresco-CSRFToken");
        }

        /**
         * Use reflection to access the JEE6 API feature setHttpOnly(boolean), if available.
         * 
         * @param cookie
         */
        private void setHttpOnly(Cookie cookie)
        {
            Class<? extends Cookie> cookieClass = cookie.getClass();
            try
            {
                Method httpOnlyMethod = cookieClass.getMethod("setHttpOnly", boolean.class);
                httpOnlyMethod.invoke(cookie, true);
                if (logger.isDebugEnabled())
                {
                    logger.debug("Cookie <"+cookie.getName()+"> set to httpOnly.");
                }
            }
            catch (
                        NoSuchMethodException |
                        SecurityException |
                        IllegalAccessException | 
                        IllegalArgumentException |
                        InvocationTargetException error)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Unable to set cookie <"+cookie.getName()+"> to httpOnly.", error);
                }
                // We do not have access to such a method, so do nothing.
            }
        }
    }
}
