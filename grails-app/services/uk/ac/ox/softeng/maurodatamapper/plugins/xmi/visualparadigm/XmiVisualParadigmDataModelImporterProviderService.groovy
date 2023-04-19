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
package uk.ac.ox.softeng.maurodatamapper.plugins.xmi.visualparadigm

import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import uk.ac.ox.softeng.maurodatamapper.api.exception.ApiBadRequestException
import uk.ac.ox.softeng.maurodatamapper.api.exception.ApiInternalException
import uk.ac.ox.softeng.maurodatamapper.api.exception.ApiUnauthorizedException
import uk.ac.ox.softeng.maurodatamapper.core.facet.Metadata
import uk.ac.ox.softeng.maurodatamapper.core.model.facet.MetadataAware
import uk.ac.ox.softeng.maurodatamapper.core.provider.importer.parameter.FileParameter
import uk.ac.ox.softeng.maurodatamapper.datamodel.DataModel
import uk.ac.ox.softeng.maurodatamapper.datamodel.item.DataClass
import uk.ac.ox.softeng.maurodatamapper.datamodel.item.DataElement
import uk.ac.ox.softeng.maurodatamapper.datamodel.item.datatype.DataType
import uk.ac.ox.softeng.maurodatamapper.datamodel.item.datatype.PrimitiveType
import uk.ac.ox.softeng.maurodatamapper.datamodel.item.datatype.ReferenceType
import uk.ac.ox.softeng.maurodatamapper.datamodel.provider.importer.DataModelImporterProviderService
import uk.ac.ox.softeng.maurodatamapper.plugins.xmi.visualparadigm.provider.importer.parameter.XmiVisualParadigmModelImporterProviderServiceParameters
import uk.ac.ox.softeng.maurodatamapper.security.User

import groovy.util.logging.Slf4j

@Slf4j
class XmiVisualParadigmDataModelImporterProviderService extends DataModelImporterProviderService<XmiVisualParadigmModelImporterProviderServiceParameters> {

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
    Boolean allowsExtraMetadataKeys() {
        true
    }

    @Override
    Boolean canImportMultipleDomains() {
        return true
    }

    @Override
    Boolean handlesContentType(String contentType) {
        contentType.equalsIgnoreCase('application/json')
    }

    @Override
    DataModel importModel(User user, XmiVisualParadigmModelImporterProviderServiceParameters params) {
        log.debug('Import model')
        importModels(user, params)?.first()
    }

    @Override
    List<DataModel> importModels(User currentUser, XmiVisualParadigmModelImporterProviderServiceParameters params) {
        if (!currentUser) throw new ApiUnauthorizedException('ART01', 'User must be logged in to import model')
        log.debug('Import Models')
        FileParameter importFile = params.importFile
        if (!importFile.fileContents.size()) throw new ApiBadRequestException('ART02', 'Cannot import empty file')
        List<DataModel> imported = []
        try {
            log.debug('Parsing in file content using XmlSlurper')
            GPathResult fileContents = xmlSlurper.parse(importFile.inputStream)
            fileContents.Model.each { umlModel ->
                Map<String, DataType> dataTypes = [:]

                DataModel dataModel = new DataModel()
                dataModel.label = unescape(umlModel.'@name')
                dataModel.createdBy = currentUser.emailAddress

                Map<String, DataClass> dataClassesById = [:]
                Map<String, DataClass> dataClassesByName = [:]

                List umlClasses = umlModel.'**'.findAll { element ->
                    element.'@xmi:type' == 'uml:Class'
                }

                List umlAssociations = umlModel.'**'.findAll { element ->
                    element.'@xmi:type' == 'uml:Association'
                }

                umlClasses.each { umlClass ->
                    String umlClassId =  umlClass.'@xmi:id'
                    String umlClassName = umlClass.'@name'
                    if(umlClassName && umlClassName != "" && !dataClassesByName[umlClassName]) {
                        DataClass dataClass = new DataClass()
                        dataClass.label = unescape(umlClassName)
                        dataClass.description = getCommentForDescription(umlClass)
                        dataClass.createdBy = currentUser.emailAddress

                        List<String> classMetadataKeys = [
                                "isAbstract", "isActive", "isLeaf", "visibility",
                                "xmi:id", "xmi:type"
                        ]

                        classMetadataKeys.each {key ->
                            addMetadata(umlClass["@${key}"], key, XMI_NAMESPACE, dataClass, currentUser)
                        }


                        dataModel.addToDataClasses(dataClass)
                        dataClassesById[umlClassId] = dataClass
                        dataClassesByName[umlClassName] = dataClass
                    } else {
                        log.warn("Duplicate or empty class name: {}", umlClassName)
                        dataClassesById[umlClassId] = dataClassesByName[umlClassName]
                    }
                }

                // Now we'll go through all the attributes...
                umlClasses.each { umlClass ->
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
                            dataElement.createdBy = currentUser.emailAddress
                            String dataTypeName = attribute.'@type'
                            if(!dataTypeName || dataTypeName == "") {
                                dataTypeName = 'Unspecified'
                            }
                            DataType dataType = dataTypes[dataTypeName]
                            if (!dataType) {
                                if(dataClassesById[dataTypeName]) {
                                    DataClass referenceDataClass = dataClassesById[dataTypeName]

                                    dataType = new ReferenceType(label: "Reference to ${referenceDataClass.label}",
                                                    referenceClass: referenceDataClass)

                                } else {
                                    dataType = new PrimitiveType(label: dataTypeName)
                                }
                                dataType.createdBy = currentUser.emailAddress
                                dataModel.addToDataTypes(dataType)
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
                                addMetadata(attribute["@${key}"], key, XMI_NAMESPACE, dataElement, currentUser)
                            }

                            dataClass.addToDataElements(dataElement)
                        }
                    }
                }

                // Now we'll iterate back over the classes and find the generalisations
                umlClasses.each { umlClass ->
                    String umlClassId =  umlClass.'@xmi:id'
                    DataClass thisDataClass = dataClassesById[umlClassId]
                    umlClass.generalization.each { generalization ->
                        String generalizeId = generalization.'@general'
                        if(dataClassesById[generalizeId]) {
                            thisDataClass.addToExtendedDataClasses(dataClassesById[generalizeId])
                            log.info("extending: {} -> {}", thisDataClass.label, dataClassesById[generalizeId].label)
                        } else {
                            log.error("No generalization possible: {} -> {}", umlClassId, generalizeId)
                        }
                    }
                }

                // Now we'll go back through the package and find associations
                umlAssociations.each { umlAssociation ->
                    GPathResult sourceOwnedEnd = umlAssociation.ownedEnd[0]
                    GPathResult targetOwnedEnd = umlAssociation.ownedEnd[1]
                    DataClass sourceClass = dataClassesById[sourceOwnedEnd.'@type']
                    DataClass targetClass = dataClassesById[targetOwnedEnd.'@type']

                    // Source data element
                    String sourceAttributeTypeName = "Reference to " + targetClass.label
                    DataType sourceAttributeType = dataTypes[sourceAttributeTypeName]
                    if(!sourceAttributeType) {
                        sourceAttributeType = new ReferenceType()
                        sourceAttributeType.label = unescape(sourceAttributeTypeName)
                        sourceAttributeType.referenceClass = targetClass
                        sourceAttributeType.createdBy = currentUser.emailAddress
                        dataModel.addToDataTypes(sourceAttributeType)
                        dataTypes[sourceAttributeTypeName] = sourceAttributeType
                    }
                    DataElement sourceAttribute = new DataElement()
                    sourceAttribute.label = unescape(umlAssociation.'@name'.toString().trim())
                    if(!sourceAttribute.label || sourceAttribute.label == "") {
                        sourceAttribute.label = unescape(targetClass.label)
                    }
                    if(sourceClass.dataElements.find { it.label == sourceAttribute.label}) {
                        sourceAttribute.label = unescape(sourceAttribute.label + " " + targetClass.label)
                    }
                    sourceAttribute.dataType = sourceAttributeType
                    sourceAttribute.description = getCommentForDescription(umlAssociation)
                    sourceAttribute.createdBy = currentUser.emailAddress
                    setMultiplicity(sourceAttribute, sourceOwnedEnd)

                    List<String> associationMetadataKeys = [
                            "aggregation", "association", "isDerived", "isDerivedUnion", "isLeaf",
                            "isReadOnly", "isStatic", "xmi:id", "xmi:type"
                    ]
                    associationMetadataKeys.each {key ->
                        addMetadata(sourceOwnedEnd["@${key}"], key, XMI_NAMESPACE, sourceAttribute, currentUser)
                    }



                    sourceClass.addToDataElements(sourceAttribute)

                    // Target data element
                    String targetAttributeTypeName = "Reference to " + sourceClass.label
                    DataType targetAttributeType = dataTypes[targetAttributeTypeName]
                    if(!targetAttributeType) {
                        targetAttributeType = new ReferenceType()
                        targetAttributeType.label = unescape(targetAttributeTypeName)
                        targetAttributeType.referenceClass = sourceClass
                        targetAttributeType.createdBy = currentUser.emailAddress
                        dataModel.addToDataTypes(targetAttributeType)
                        dataTypes[targetAttributeTypeName] = targetAttributeType
                    }
                    DataElement targetAttribute = new DataElement()
                    targetAttribute.label = unescape(umlAssociation.'@name'.toString().trim())
                    if(!targetAttribute.label || targetAttribute.label == "") {
                        targetAttribute.label = unescape(sourceClass.label)
                    }
                    if(targetClass.dataElements.find { it.label == targetAttribute.label}) {
                        targetAttribute.label = unescape(targetAttribute.label + " " + sourceClass.label)
                    }
                    targetAttribute.dataType = targetAttributeType
                    targetAttribute.description = getCommentForDescription(umlAssociation)
                    targetAttribute.createdBy = currentUser.emailAddress
                    setMultiplicity(targetAttribute, targetOwnedEnd)

                    associationMetadataKeys.each {key ->
                        addMetadata(targetOwnedEnd["@${key}"], key, XMI_NAMESPACE, targetAttribute, currentUser)
                    }

                    targetClass.addToDataElements(targetAttribute)

                }

                imported.add(dataModel)
            }

        } catch (Exception ex) {
            throw new ApiInternalException('ART03', 'Could not import XMI (Visual Paradigm) models', ex)
        }


        imported
    }



    String getCommentForDescription(GPathResult xmlNode) {
        String description = ""

        if(xmlNode.ownedComment.size() > 0) {
            if(xmlNode.ownedComment.Extension.size() > 0) {
                description = xmlNode.ownedComment.Extension.htmlValue.'@value'
            } else if(xmlNode.ownedComment.body.size() > 0) {
                description = xmlNode.ownedComment.body.text()
            }
        }
        if(description == "") {
            return null
        }
        return description.replace("\\u00a0", " ").replaceAll("\\s+", " ").trim()
    }

    static void setMultiplicity(DataElement dataElement, GPathResult umlNode) {
        String lowerValue = umlNode.lowerValue?.'@value'
        String upperValue = umlNode.upperValue?.'@value'

        if(lowerValue) {
            dataElement.minMultiplicity = Integer.parseInt(lowerValue)
        }
        if(upperValue && upperValue == "*") {
            dataElement.maxMultiplicity = -1
        } else if(upperValue) {
            dataElement.maxMultiplicity = Integer.parseInt(upperValue)
        }
    }

    static void addMetadata(def value, String key, String namespace, MetadataAware catalogueItem, User user) {
        if(value && value.toString() != "") {
            Metadata md = new Metadata( value: value.toString(), key: key, namespace: namespace, createdBy: user.emailAddress)
            catalogueItem.addToMetadata(md)
        }
    }

    static String unescape(def input) {
        String inputStr = input.toString()
        inputStr.replace('%20', ' ')
    }


}
