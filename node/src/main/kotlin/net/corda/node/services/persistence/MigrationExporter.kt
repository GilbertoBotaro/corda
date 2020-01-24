package net.corda.node.services.persistence

import net.corda.core.identity.AbstractParty
import net.corda.nodeapi.internal.MigrationHelpers.defaultMigrationResourceNameForSchema
import net.corda.core.internal.objectOrNewInstance
import net.corda.core.schemas.MappedSchema
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.HibernateConfiguration.Companion.buildHibernateMetadata
import org.hibernate.boot.Metadata
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.cfg.AvailableSettings.CONNECTION_PROVIDER
import org.hibernate.cfg.Configuration
import org.hibernate.cfg.Environment
import org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl
import org.hibernate.tool.hbm2ddl.SchemaExport
import org.hibernate.tool.schema.TargetType
import java.io.File
import java.nio.file.Path
import java.util.*
import javax.persistence.AttributeConverter
import javax.persistence.Converter
import javax.sql.DataSource

/**
 * This is useful for CorDapp developers who want to enable migrations for
 * standard "Open Source" Corda CorDapps
 */
class MigrationExporter(val parent: Path, val datasourceProperties: Properties, val cordappClassLoader: ClassLoader, val dataSource: DataSource) {

    companion object {
        const val LIQUIBASE_HEADER = "--liquibase formatted sql"
        const val CORDA_USER = "R3.Corda.Generated"
    }

    fun generateMigrationForCorDapp(schemaName: String): Pair<Class<*>, Path> {
        val schemaClass = cordappClassLoader.loadClass(schemaName)
        val schemaObject = schemaClass.kotlin.objectOrNewInstance() as MappedSchema
        return generateMigrationForCorDapp(schemaObject)
    }

    fun generateMigrationForCorDapp(mappedSchema: MappedSchema): Pair<Class<*>, Path> {

        //create hibernate metadata for MappedSchema
        val metadata = createHibernateMetadataForSchema(mappedSchema)

        //create output file and add liquibase headers
        val outputFile = File(parent.toFile(), "${defaultMigrationResourceNameForSchema(mappedSchema)}.sql")
        outputFile.apply {
            parentFile.mkdirs()
            delete()
            createNewFile()
            appendText(LIQUIBASE_HEADER)
            appendText("\n\n")
            appendText("--changeset ${CORDA_USER}:initial_schema_for_${mappedSchema::class.simpleName!!}")
            appendText("\n")
        }

        //export the schema to that file
        SchemaExport().apply {
            setDelimiter(";")
            setFormat(true)
            setOutputFile(outputFile.absolutePath)
            execute(EnumSet.of(TargetType.SCRIPT), SchemaExport.Action.CREATE, metadata)
        }
        return mappedSchema::class.java to outputFile.toPath()
    }

    private fun createHibernateMetadataForSchema(mappedSchema: MappedSchema): Metadata {
        val metadataSources = MetadataSources(BootstrapServiceRegistryBuilder().build())
        val config = Configuration(metadataSources)
                .setProperty(CONNECTION_PROVIDER, DatasourceConnectionProviderImpl::class.java.name)

        mappedSchema.mappedTypes.forEach { config.addAnnotatedClass(it) }

        val registryBuilder = config.standardServiceRegistryBuilder
                .addService(org.hibernate.boot.registry.classloading.spi.ClassLoaderService::class.java, ClassLoaderServiceImpl(cordappClassLoader))
                .applySettings(config.properties)
                .applySetting(Environment.DATASOURCE, dataSource)

        val metadataBuilder = @Suppress("DEPRECATION") metadataSources.getMetadataBuilder(registryBuilder.build())

        return buildHibernateMetadata(metadataBuilder, datasourceProperties.getProperty(CordaPersistence.DataSourceConfigTag.DATA_SOURCE_URL),
                listOf(DummyAbstractPartyToX500NameAsStringConverter()))
    }

    /**
     * used just for generating columns
     */
    @Converter(autoApply = true)
    class DummyAbstractPartyToX500NameAsStringConverter : AttributeConverter<AbstractParty, String> {
        override fun convertToDatabaseColumn(party: AbstractParty?) = null
        override fun convertToEntityAttribute(dbData: String?) = null
    }
}