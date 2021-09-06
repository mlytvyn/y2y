
package com.github.mlytvyn.y2y.hac.flexiblesearch;

import com.sap.hybris.hac.Configuration;
import com.sap.hybris.hac.HybrisAdministrationConsole;
import com.sap.hybris.hac.flexiblesearch.FlexibleSearchQuery;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Y2YFlexibleSearch {

    private final static Map<String, HybrisAdministrationConsole> CACHED_HAC_CLIENTS = new ConcurrentHashMap<>();

    public static List<Map<String, String>> getData(final String requestQuery, final int maxRows, final String url, final String username, final String password) {
        return CACHED_HAC_CLIENTS.computeIfAbsent(url + username + password, key -> HybrisAdministrationConsole.hac(Configuration.builder()
            .endpoint(url)
            .username(username)
            .password(password)
            .build()
        ))
            .flexibleSearch()
            .query(
                FlexibleSearchQuery.builder()
                    .flexibleSearchQuery(requestQuery)
                    .maxCount(maxRows)
                    .build()
            )
            .asMap();
    }
}
