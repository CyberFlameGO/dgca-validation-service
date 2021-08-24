/*-
 * ---license-start
 * eu-digital-green-certificates / dgca-validation-service
 * ---
 * Copyright (C) 2021 T-Systems International GmbH and all other contributors
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package eu.europa.ec.dgc.validation.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

@Getter
@Setter
@ConfigurationProperties("dgc")
public class DgcConfigProperties {

    private final GatewayDownload businessRulesDownload = new GatewayDownload();

    private final GatewayDownload valueSetsDownload = new GatewayDownload();

    private final GatewayDownload countryListDownload = new GatewayDownload();

    @Getter
    @Setter
    public static class GatewayDownload {
        private Integer timeInterval;
        private Integer lockLimit;
    }

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration validationExpire = Duration.ofMinutes(60);

    private String serviceUrl;
}