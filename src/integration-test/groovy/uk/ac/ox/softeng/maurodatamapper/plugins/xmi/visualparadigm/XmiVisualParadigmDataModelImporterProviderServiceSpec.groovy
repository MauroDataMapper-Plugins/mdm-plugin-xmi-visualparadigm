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

import uk.ac.ox.softeng.maurodatamapper.core.container.Folder
import uk.ac.ox.softeng.maurodatamapper.core.facet.Metadata
import uk.ac.ox.softeng.maurodatamapper.core.provider.importer.parameter.FileParameter
import uk.ac.ox.softeng.maurodatamapper.datamodel.DataModel
import uk.ac.ox.softeng.maurodatamapper.datamodel.DataModelService
import uk.ac.ox.softeng.maurodatamapper.datamodel.item.DataClass
import uk.ac.ox.softeng.maurodatamapper.datamodel.item.DataElement
import uk.ac.ox.softeng.maurodatamapper.plugins.xmi.visualparadigm.provider.importer.parameter.XmiVisualParadigmModelImporterProviderServiceParameters
import uk.ac.ox.softeng.maurodatamapper.test.integration.BaseIntegrationSpec

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import grails.util.BuildSettings
import groovy.util.logging.Slf4j
import spock.lang.Shared

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Slf4j
@Integration
@Rollback
class XmiVisualParadigmDataModelImporterProviderServiceSpec extends BaseIntegrationSpec {

    DataModelService dataModelService

    XmiVisualParadigmModelImporterProviderServiceParameters artDecorDataModelImporterProviderService

    @Shared
    Path resourcesPath

    def setupSpec() {
        resourcesPath = Paths.get(BuildSettings.BASE_DIR.absolutePath, 'src', 'integration-test', 'resources').toAbsolutePath()
    }

    @Override
    void setupDomainData() {

        folder = new Folder(label: 'catalogue', createdBy: admin.emailAddress)
        checkAndSave(folder)
        //        testAuthority = new Authority(label: 'Test Authority', url: 'http://localhost', createdBy: StandardEmailAddress.INTEGRATION_TEST)
        //        checkAndSave(testAuthority)
    }

    byte[] loadTestFile(String filename) {
        Path testFilePath = resourcesPath.resolve("${filename}").toAbsolutePath()
        assert Files.exists(testFilePath)
        Files.readAllBytes(testFilePath)
    }

    def "verify artdecor-test-multiple-concepts"() {
        given:
        setupDomainData()
        def parameters = new XmiVisualParadigmModelImporterProviderServiceParameters(
            folderId: folder.id,
            importFile: new FileParameter(fileContents: loadTestFile('artdecor-test-multiple-concepts.json'))
        )

        when:
        def dataModels = artDecorDataModelImporterProviderService.importDomains(admin, parameters)

        then:
        dataModels.size() == 1
        !dataModels.first().id
        dataModels.first().childDataClasses.size() == 37

        when:
        dataModels.first().folder = folder
        check(dataModels.first())

        then:
        !dataModels.first().hasErrors()

        when:
        DataModel saved = dataModelService.saveModelWithContent(dataModels.first())

        then:
        //dataModel
        saved.id
        saved.label == 'Core information standard'
        saved.childDataClasses.size() == 37
        saved.dataClasses.size() == 497
        saved.primitiveTypes.size() == 100
        saved.metadata.size() == 14

        when:
        DataClass dataClass = saved.childDataClasses.find {it.label == 'Person demographics'}

        then:
        dataClass
        dataClass.description == 'The person\'s details and contact information.'
        dataClass.maxMultiplicity == 1
        dataClass.minMultiplicity == 1
        Metadata.countByMultiFacetAwareItemId(dataClass.id) == 10
        //dataElements
        //You should absolutely define the dataclass or element you're hitting NOT the first thing in the sub list
        dataClass.dataElements.size() == 13

        when:
        DataElement dataElement = dataClass.dataElements.find {it.label == 'Date of birth'}

        then:
        dataElement
        dataElement.description == 'The date of birth of the person.'
        dataElement.maxMultiplicity == 1
        dataElement.minMultiplicity == 1
        dataElement.dataType.label == 'date'
        dataElement.metadata.size() == 14

        dataClass.dataClasses.size() == 4
    }

    def "verify artDecor-payload-2"() {
        given:
        def parameters = new XmiVisualParadigmModelImporterProviderServiceParameters(
            importFile: new FileParameter(fileContents: loadTestFile('artDecor-payload-2.json'))
        )

        when:
        DataModel dataModel = artDecorDataModelImporterProviderService.importModel(admin, parameters)

        then:
        dataModel.label == 'Local authority information'
    }


}
