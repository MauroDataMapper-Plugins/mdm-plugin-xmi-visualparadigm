/*
 * Copyright 2020-2023 University of Oxford and NHS England
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
package uk.ac.ox.softeng.mauro.plugins.xmi.visualparadigm.importer

import uk.ac.ox.softeng.mauro.domain.datamodel.DataModel
import uk.ac.ox.softeng.mauro.plugin.MauroPlugin
import uk.ac.ox.softeng.mauro.plugin.MauroPluginService
import uk.ac.ox.softeng.mauro.plugin.importer.FileImportParameters
import uk.ac.ox.softeng.mauro.plugin.importer.FileParameter

import groovy.util.logging.Slf4j
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@Slf4j
@MicronautTest
class XmiVisualParadigmDataModelImporterPluginSpec extends Specification {

    static String NAMESPACE = 'uk.ac.ox.softeng.mauro.plugins.xmi.visualparadigm.importer'
    static String NAME = 'XmiVisualParadigmDataModelImporterPlugin'

    @Inject
    MauroPluginService mauroPluginService

    def "test we can find the plugin"() {
        when:
        List<MauroPlugin> pluginList = mauroPluginService.listPlugins()

        then:
        pluginList.find {
            it.displayName == 'XMI (Visual Paradigm) Importer' &&
                it.namespace == NAMESPACE &&
                it.version == 'SNAPSHOT'
        }

    }

    def "test a simple import"() {
        when:
        XmiVisualParadigmDataModelImporterPlugin importerPlugin = mauroPluginService.getPlugin(XmiVisualParadigmDataModelImporterPlugin, NAMESPACE, NAME)

        FileImportParameters fileImportParameters = new FileImportParameters()
        FileParameter fileParameter = new FileParameter()
        fileParameter.fileName = "testModel.xmi"
        fileParameter.fileType = "application/xml"
        fileParameter.fileContents = this.class.classLoader.getResourceAsStream('testModel.xmi').readAllBytes()
        fileImportParameters.importFile = fileParameter
        List<DataModel> dataModels = importerPlugin.importModels(fileImportParameters)

        then:
        dataModels.size() == 1
        dataModels.first().label == 'testModel'
        dataModels.first().dataClasses.size() == 4
        dataModels.first().dataClasses.label.sort() == ['Author','Book','Person','Thing']
    }







}
