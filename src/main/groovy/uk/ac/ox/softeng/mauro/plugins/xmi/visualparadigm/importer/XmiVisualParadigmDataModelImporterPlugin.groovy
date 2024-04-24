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

import uk.ac.ox.softeng.mauro.domain.datamodel.DataClass
import uk.ac.ox.softeng.mauro.domain.datamodel.DataElement
import uk.ac.ox.softeng.mauro.domain.datamodel.DataModel
import uk.ac.ox.softeng.mauro.domain.datamodel.DataType
import uk.ac.ox.softeng.mauro.domain.facet.Metadata
import uk.ac.ox.softeng.mauro.domain.model.AdministeredItem
import uk.ac.ox.softeng.mauro.plugin.importer.DataModelImporterPlugin
import uk.ac.ox.softeng.mauro.plugin.importer.FileImportParameters
import uk.ac.ox.softeng.mauro.plugin.importer.FileParameter

import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import io.micronaut.context.annotation.Bean
import org.apache.commons.text.StringEscapeUtils

import groovy.util.logging.Slf4j

@Slf4j
@Bean
class XmiVisualParadigmDataModelImporterPlugin implements DataModelImporterPlugin<FileImportParameters> {

    private static XmlSlurper xmlSlurper = new XmlSlurper()

    final static String VISUAL_PARADIGM_NAMESPACE = "uk.ac.ox.softeng.maurodatamapper.visualparadigm"
    final static String XMI_NAMESPACE = "uk.ac.ox.softeng.maurodatamapper.xmi"

    @Override
    String getDisplayName() {
        'XMI (Visual Paradigm) Importer'
    }

    @Override
    String getVersion() {
        getClass().getPackage().getSpecificationVersion() ?: 'SNAPSHOT'
    }


    @Override
    List<DataModel> importDomain(FileImportParameters params) {
        log.debug('Import Models')
        FileParameter importFile = params.importFile
        if (!importFile.fileContents.size()) throw new Exception('Cannot import empty file')
        List<DataModel> imported = []
        try {
            log.debug('Parsing in file content using XmlSlurper')
            GPathResult fileContents = xmlSlurper.parse(importFile.inputStream)
            fileContents.Model.each { umlModel ->
                Map<String, DataType> dataTypes = [:]

                DataModel dataModel = new DataModel()
                dataModel.label = unescape(umlModel.'@name')

                Map<String, DataClass> dataClassesById = [:]
                Map<String, DataClass> dataClassesByName = [:]

                Map<String, Object> umlPackageClassMap = [:]

                addClassesToPackageMap(umlModel, "", umlPackageClassMap)

                List umlAssociations = umlModel.'**'.findAll { element ->
                    element.'@xmi:type' == 'uml:Association'
                }

                umlPackageClassMap.each { packagePath, umlClass ->


                    String umlClassId =  umlClass.'@xmi:id'
                    String umlClassName = umlClass.'@name'
                    if(umlClassName && umlClassName != "" && !dataClassesByName[umlClassName]) {
                        DataClass dataClass = new DataClass()
                        dataClass.label = unescape(umlClassName)
                        dataClass.description = getCommentForDescription(umlClass)

                        List<String> classMetadataKeys = [
                            "isAbstract", "isActive", "isLeaf", "visibility",
                            "xmi:id", "xmi:type"
                        ]

                        classMetadataKeys.each {key ->
                            addMetadata(umlClass["@${key}"], key, XMI_NAMESPACE, dataClass)
                        }

                        addMetadata(umlClass.ownedComment.Extension.htmlValue.'@value', "Owned Comment VP Extension", XMI_NAMESPACE, dataClass)
                        addMetadata(packagePath, "packagePath", XMI_NAMESPACE, dataClass)

                        dataModel.dataClasses.add(dataClass)
                        dataClassesById[umlClassId] = dataClass
                        dataClassesByName[umlClassName] = dataClass
                    } else {
                        log.warn("Duplicate or empty class name: {}", umlClassName)
                        dataClassesById[umlClassId] = dataClassesByName[umlClassName]
                    }
                }

                // Now we'll go through all the attributes...
                umlPackageClassMap.values().each { umlClass ->
                    String umlClassId =  umlClass.'@xmi:id'
                    DataClass dataClass = dataClassesById[umlClassId]
                    if(!dataClass) {
                        log.error("No data class found for id: ${umlClassId}" )
                    }
                    umlClass.ownedAttribute.each { attribute ->
                        if(!attribute.@name || attribute.@name.toString() == "") {
                            log.error("Element with no name (classId: umlClassId")
                        } else if(!dataClass.dataElements.find {it.label.equalsIgnoreCase(attribute.@name.toString())}) {
                            DataElement dataElement = new DataElement()
                            dataElement.label = unescape(attribute.@name)
                            dataElement.description = getCommentForDescription(attribute)
                            String dataTypeName = attribute.'@type'
                            if(!dataTypeName || dataTypeName == "") {
                                dataTypeName = 'Unspecified'
                            }
                            DataType dataType = dataTypes[dataTypeName]
                            if (!dataType) {
                                if(dataClassesById[dataTypeName]) {
                                    DataClass referenceDataClass = dataClassesById[dataTypeName]

                                    dataType = new DataType(label: "Reference to ${referenceDataClass.label}",
                                                                 referenceClass: referenceDataClass,
                                                                dataTypeKind: DataType.DataTypeKind.REFERENCE_TYPE)

                                } else {
                                    dataType = new DataType(label: dataTypeName, dataTypeKind: DataType.DataTypeKind.PRIMITIVE_TYPE)
                                }
                                dataModel.dataTypes.add(dataType)
                                dataTypes[dataTypeName] = dataType
                            }
                            dataElement.dataType = dataType

                            setMultiplicity(dataElement, attribute)

                            List<String> attributeMetadataKeys = [
                                'aggregation', 'isDerived', 'isDerivedUnion', 'isID', 'isLeaf',
                                'isOrdered', 'isReadOnly', 'isStatic', 'isUnique', 'visibility',
                                'xmi:id', 'xmi:type'
                            ]
                            attributeMetadataKeys.each {key ->
                                addMetadata(attribute["@${key}"], key, XMI_NAMESPACE, dataElement)
                            }

                            addMetadata(attribute.ownedComment.Extension.htmlValue.'@value', "Owned Comment VP Extension", XMI_NAMESPACE, dataElement)

                            dataClass.dataElements.add(dataElement)
                        }
                    }
                }

                // Now we'll iterate back over the classes and find the generalisations
                umlPackageClassMap.values().each { umlClass ->
                    String umlClassId =  umlClass.'@xmi:id'
                    DataClass thisDataClass = dataClassesById[umlClassId]
                    umlClass.generalization.each { generalization ->
                        String generalizeId = generalization.'@general'
                        if(dataClassesById[generalizeId]) {
                            thisDataClass.extendsDataClasses.add(dataClassesById[generalizeId])
                            log.info("extending: {} -> {}", thisDataClass.label, dataClassesById[generalizeId].label)
                        } else {
                            log.error("No generalization possible: {} -> {}", umlClassId, generalizeId)
                        }
                    }
                }

                // Now we'll go back through the package and find associations
                umlAssociations
                    .findAll { it.ownedEnd.size() == 2}
                    .each { umlAssociation ->
                        try {

                            GPathResult sourceOwnedEnd = umlAssociation.ownedEnd[0]
                            GPathResult targetOwnedEnd = umlAssociation.ownedEnd[1]

                            DataClass sourceClass = dataClassesById[getOwnedEndTypeId(sourceOwnedEnd)]
                            DataClass targetClass = dataClassesById[getOwnedEndTypeId(targetOwnedEnd)]


                            // Source data element
                            String sourceAttributeTypeName = "Reference to " + targetClass.label
                            DataType sourceAttributeType = dataTypes[sourceAttributeTypeName]
                            if (!sourceAttributeType) {
                                sourceAttributeType = new DataType(dataTypeKind: DataType.DataTypeKind.REFERENCE_TYPE)
                                sourceAttributeType.label = unescape(sourceAttributeTypeName)
                                //sourceAttributeType.referenceClass = targetClass
                                dataModel.dataTypes.add(sourceAttributeType)
                                dataTypes[sourceAttributeTypeName] = sourceAttributeType
                            }
                            DataElement sourceAttribute = new DataElement()
                            sourceAttribute.label = unescape(umlAssociation.'@name'.toString().trim())
                            if (!sourceAttribute.label || sourceAttribute.label == "") {
                                sourceAttribute.label = unescape(targetClass.label)
                            }
                            if (sourceClass.dataElements.find { it.label == sourceAttribute.label }) {
                                sourceAttribute.label = unescape(sourceAttribute.label + " " + targetClass.label)
                            }
                            sourceAttribute.dataType = sourceAttributeType
                            sourceAttribute.description = getCommentForDescription(umlAssociation)
                            setMultiplicity(sourceAttribute, targetOwnedEnd)

                            List<String> associationMetadataKeys = [
                                "aggregation", "association", "isDerived", "isDerivedUnion", "isLeaf", "isNavigable",
                                "isReadOnly", "isStatic", "visibility", "xmi:id", "xmi:type"
                            ]
                            associationMetadataKeys.each { key ->
                                addMetadata(sourceOwnedEnd["@${key}"], key, XMI_NAMESPACE, sourceAttribute)
                            }
                            Tuple2<Integer, Integer> oppositeMultiplicity = convertMultiplicity(sourceOwnedEnd)
                            addMetadata(oppositeMultiplicity.getV1().toString(), "oppositeMinMultiplicity", XMI_NAMESPACE, sourceAttribute)
                            addMetadata(oppositeMultiplicity.getV2().toString(), "oppositeMaxMultiplicity", XMI_NAMESPACE, sourceAttribute)
                            sourceClass.dataElements.add(sourceAttribute)

                            /*
                                    // Target data element
                                    String targetAttributeTypeName = "Reference to " + sourceClass.label
                                    DataType targetAttributeType = dataTypes[targetAttributeTypeName]
                                    if (!targetAttributeType) {
                                        targetAttributeType = new ReferenceType()
                                        targetAttributeType.label = unescape(targetAttributeTypeName)
                                        targetAttributeType.referenceClass = sourceClass
                                        targetAttributeType.createdBy = currentUser.emailAddress
                                        dataModel.addToDataTypes(targetAttributeType)
                                        dataTypes[targetAttributeTypeName] = targetAttributeType
                                    }
                                    DataElement targetAttribute = new DataElement()
                                    targetAttribute.label = unescape(umlAssociation.'@name'.toString().trim())
                                    if (!targetAttribute.label || targetAttribute.label == "") {
                                        targetAttribute.label = unescape(sourceClass.label)
                                    }
                                    if (targetClass.dataElements.find { it.label == targetAttribute.label }) {
                                        targetAttribute.label = unescape(targetAttribute.label + " " + sourceClass.label)
                                    }
                                    targetAttribute.dataType = targetAttributeType
                                    targetAttribute.description = getCommentForDescription(umlAssociation)
                                    targetAttribute.createdBy = currentUser.emailAddress
                                    setMultiplicity(targetAttribute, targetOwnedEnd)

                                    associationMetadataKeys.each { key ->
                                        addMetadata(targetOwnedEnd["@${key}"], key, XMI_NAMESPACE, targetAttribute, currentUser)
                                    }

                                    targetClass.addToDataElements(targetAttribute)

                             */
                        } catch (Exception e) {
                            // We've been unable to create an association - swallow it for now, but log
                            log.debug("Unable to create association")
                            log.debug(umlAssociation.toString())
                            log.debug(e.getMessage())
                            log.debug(e.stackTrace.toString())
                        }
                    }

                imported.add(dataModel)
            }

        } catch (Exception ex) {
            throw new Exception('Could not import XMI (Visual Paradigm) models', ex)
        }


        imported

    }

    @Override
    Boolean handlesContentType(String contentType) {
        contentType.equalsIgnoreCase('application/json')
    }

    @Override
    Class<FileImportParameters> importParametersClass() {
        return FileImportParameters
    }





    static String getCommentForDescription(GPathResult xmlNode) {
        String description = ""

        if(xmlNode.ownedComment.size() > 0) {
            if(xmlNode.ownedComment.body.size() > 0) {
                description = xmlNode.ownedComment.body.text()
            } else if(xmlNode.ownedComment.Extension.size() > 0) {
                description = xmlNode.ownedComment.Extension.htmlValue.'@value'
            }
        }
        if(description == "") {
            return null
        }
        //description = description.replaceAll("\\s+", " ").trim()
        description = description.trim()
        description = StringEscapeUtils.unescapeJava(description)

        return description
    }


    static Tuple2<Integer, Integer> convertMultiplicity(GPathResult umlNode) {
        Integer minMultiplicity = null
        Integer maxMultiplicity = null

        String lowerValue = umlNode.lowerValue?.'@value'
        String upperValue = umlNode.upperValue?.'@value'

        if(lowerValue) {
            minMultiplicity = Integer.parseInt(lowerValue)
        }
        if(upperValue && upperValue == "*") {
            maxMultiplicity = -1
        } else if(upperValue) {
            maxMultiplicity = Integer.parseInt(upperValue)
        } else {
            maxMultiplicity = minMultiplicity
        }
        return new Tuple2<Integer, Integer> (minMultiplicity, maxMultiplicity)
    }

    static void setMultiplicity(DataElement dataElement, GPathResult umlNode) {
        Tuple2<Integer, Integer> multiplicities = convertMultiplicity(umlNode)

        dataElement.minMultiplicity = multiplicities.getV1()
        dataElement.maxMultiplicity = multiplicities.getV2()
    }

    static void addMetadata(def value, String key, String namespace, AdministeredItem catalogueItem) {
        if(value && value.toString() != "") {
            Metadata md = new Metadata(value: value.toString(), key: key, namespace: namespace)
            catalogueItem.metadata.add(md)
        }
    }

    static String unescape(def input) {
        String inputStr = input.toString()
        inputStr.replace('%20', ' ')
    }


    static void addClassesToPackageMap(def packageNode, String currentPath, Map<String, Object> packageClassMap) {
        packageNode.'*'.each { childNode ->
            if(childNode.'@name') {
                String newPath = "${currentPath}.${childNode.'@name'}"
                if(currentPath == "") {
                    newPath = childNode.'@name'
                }
                if(childNode.'@xmi:type' == 'uml:Class') {
                    packageClassMap[newPath] = childNode
                }
                else if(childNode.'@xmi:type' == 'uml:Package') {
                    addClassesToPackageMap(childNode, newPath, packageClassMap)
                }

            }
        }
    }

    static String getOwnedEndTypeId(GPathResult ownedEnd) {
        if(ownedEnd.'@type') {
            return ownedEnd.'@type'.toString()
        } else if(ownedEnd.type && ownedEnd.type.'@idref') {
            return ownedEnd.type.'@idref'.toString()
        } else {
            return null
        }
    }


}
