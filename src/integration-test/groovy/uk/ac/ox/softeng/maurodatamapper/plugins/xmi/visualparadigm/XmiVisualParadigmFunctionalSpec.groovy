/*
 * Copyright 2020-2022 University of Oxford and Health and Social Care Information Centre, also known as NHS Digital
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
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package uk.ac.ox.softeng.maurodatamapper.plugins.xmi.visualparadigm

import uk.ac.ox.softeng.maurodatamapper.datamodel.DataModel
import uk.ac.ox.softeng.maurodatamapper.plugins.xmi.visualparadigm.XmiVisualParadigmDataModelImporterProviderService
import uk.ac.ox.softeng.maurodatamapper.test.functional.BaseFunctionalSpec

import grails.core.GrailsApplication
import grails.testing.mixin.integration.Integration
import grails.util.BuildSettings
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import spock.lang.Ignore
import spock.lang.Shared

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static io.micronaut.http.HttpStatus.OK

@Slf4j
@Integration
class XmiVisualParadigmFunctionalSpec extends BaseFunctionalSpec {

    @Shared
    Path resourcesPath

    GrailsApplication grailsApplication
    XmiVisualParadigmDataModelImporterProviderService xmiVisualParadigmDataModelImporterProviderService

    def setupSpec() {
        resourcesPath = Paths.get(BuildSettings.BASE_DIR.absolutePath, 'src', 'integration-test', 'resources').toAbsolutePath()
    }

    @Override
    String getResourcePath() {
        ''
    }

    byte[] loadTestFile(String filename) {
        Path testFilePath = resourcesPath.resolve("${filename}").toAbsolutePath()
        assert Files.exists(testFilePath)
        Files.readAllBytes(testFilePath)
    }

    void 'test importer parameters'() {
        given:
        String version = xmiVisualParadigmDataModelImporterProviderService.version
        String expected = new String(loadTestFile('expectedImporterParameters.json'))
        String url = "importer/parameters/uk.ac.ox.softeng.maurodatamapper.plugins.xmi.visualparadigm/XmiVisualParadigmDataModelImporterProviderService/$version"

        when:
        GET(url, STRING_ARG)

        then:
        verifyJsonResponse OK, expected
    }

}
