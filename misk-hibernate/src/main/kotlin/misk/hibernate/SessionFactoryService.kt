package misk.hibernate

import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.AbstractIdleService
import misk.jdbc.DataSourceConnector
import misk.logging.getLogger
import okio.ByteString
import org.hibernate.SessionFactory
import org.hibernate.boot.Metadata
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.boot.spi.MetadataImplementor
import org.hibernate.cfg.AvailableSettings
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.integrator.spi.Integrator
import org.hibernate.mapping.Property
import org.hibernate.mapping.SimpleValue
import org.hibernate.mapping.Value
import org.hibernate.service.spi.SessionFactoryServiceRegistry
import org.hibernate.usertype.UserType
import javax.inject.Provider
import javax.sql.DataSource
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

private val logger = getLogger<SessionFactoryService>()

/**
 * Builds a bare connection to a Hibernate database. Doesn't do any schema migration or validation.
 */
internal class SessionFactoryService(
  private val qualifier: KClass<out Annotation>,
  private val connector: DataSourceConnector,
  private val dataSource: Provider<DataSource>,
  private val hibernateInjectorAccess: HibernateInjectorAccess,
  private val entityClasses: Set<HibernateEntity> = setOf(),
  private val listenerRegistrations: Set<ListenerRegistration> = setOf()
) : AbstractIdleService(), Provider<SessionFactory>, TransacterService {
  private var sessionFactory: SessionFactory? = null

  lateinit var hibernateMetadata: Metadata

  override fun startUp() {
    val stopwatch = Stopwatch.createStarted()
    logger.info("Starting @${qualifier.simpleName} Hibernate")

    require(sessionFactory == null)

    // Register event listeners.
    val integrator = object : Integrator {

      override fun integrate(
        metadata: Metadata,
        sessionFactory: SessionFactoryImplementor,
        serviceRegistry: SessionFactoryServiceRegistry
      ) {
        val eventListenerRegistry = serviceRegistry.getService(EventListenerRegistry::class.java)
        val aggregateListener = AggregateListener(listenerRegistrations)
        aggregateListener.registerAll(eventListenerRegistry)

        hibernateMetadata = metadata
      }

      override fun disintegrate(
        sessionFactory: SessionFactoryImplementor,
        serviceRegistry: SessionFactoryServiceRegistry
      ) {
      }
    }
    val bootstrapRegistryBuilder = BootstrapServiceRegistryBuilder()
        .applyIntegrator(integrator)
        .build()

    val registryBuilder = StandardServiceRegistryBuilder(bootstrapRegistryBuilder)
    registryBuilder.addInitiator(hibernateInjectorAccess)
    val config = connector.config()
    registryBuilder.run {
      applySetting(AvailableSettings.DATASOURCE, dataSource.get())
      applySetting(AvailableSettings.DIALECT, config.type.hibernateDialect)
      applySetting(AvailableSettings.SHOW_SQL, config.show_sql)
      applySetting(AvailableSettings.USE_SQL_COMMENTS, "true")
      applySetting(AvailableSettings.USE_GET_GENERATED_KEYS, "true")
      applySetting(AvailableSettings.USE_NEW_ID_GENERATOR_MAPPINGS, "false")
      applySetting(AvailableSettings.JDBC_TIME_ZONE, "UTC")
    }

    val registry = registryBuilder.build()

    // Register entity types.
    val metadataSources = MetadataSources(registry)
    for (entityClass in entityClasses) {
      metadataSources.addAnnotatedClass(entityClass.entity.java)
    }

    // NB: MetadataSources.getMetadataBuilder() returns a different instance each call!
    val metadataDraftBuilder = metadataSources.metadataBuilder
    // After `build` is called Hibernate creates ID Generators so we have to register this custom
    // type before we call build, otherwise Id will be treated as a SerializableType instead.
    // See: Component#createIdentifierGenerator
    val metadataDraft = metadataDraftBuilder.build() as MetadataImplementor

    // Loop over all of the properties in all of the entities so we can set up UserTypes.
    val allPropertyTypes = mutableSetOf<KClass<*>>()
    for ((persistentClass, property) in metadataDraft.allProperties.entries()) {
      processPropertyAnnotations(persistentClass, property)

      val value: Value? = property.value
      if (value is SimpleValue) {
        allPropertyTypes += kClassForName(value.typeName)
      }
    }

    val metadataBuilder = metadataSources.metadataBuilder

    // Register custom type adapters so we can have columns for ByteString, Id, etc. This needs to
    // happen after we know what all of the property classes are, but before Hibernate validates
    // that their adapters exist.
    for (propertyType in allPropertyTypes) {
      val userType = findUserType(propertyType)
      if (userType != null) {
        metadataBuilder.applyBasicType(userType, propertyType.jvmName)
      }
    }

    val metadata = metadataBuilder.build() as MetadataImplementor

    for ((persistentClass, property) in metadata.allProperties.entries()) {
      processPropertyAnnotations(persistentClass, property)
    }
    sessionFactory = metadata.buildSessionFactory()

    logger.info("Started @${qualifier.simpleName} Hibernate in $stopwatch")
  }

  /**
   * When a type is annotated we customize it! For example, this is where we hookup support for
   * @JsonColumn.
   */
  private fun processPropertyAnnotations(
    persistentClass: Class<*>,
    property: Property
  ) {
    val value = property.value as? SimpleValue ?: return

    val field = field(persistentClass, property)
    if (field.isAnnotationPresent(JsonColumn::class.java)) {
      value.typeName = JsonColumnType::class.java.name
      value.setTypeParameter("jsonColumnField", field)
    } else if (field.isAnnotationPresent(ProtoColumn::class.java)) {
      value.typeName = ProtoColumnType::class.java.name
      value.setTypeParameter("protoColumnField", field)
    } else if (field.isAnnotationPresent(SecretColumn::class.java)) {
      value.typeName = SecretColumnType::class.java.name
      value.setTypeParameter(SecretColumnType.FIELD_ENCRYPTION_KEY_NAME,
          field.getAnnotation(SecretColumn::class.java).keyName)
      value.setTypeParameter(SecretColumnType.FIELD_ENCRYPTION_INDEXABLE,
          field.getAnnotation(SecretColumn::class.java).indexable.toString())
    } else if (BoxedStringType.isBoxedString(field.type.kotlin)) {
      value.typeName = BoxedStringType::class.java.name
      value.setTypeParameter("boxedStringField", field)
    }
  }

  /** Returns a custom user type for `propertyType`, or null if the user type should be built-in. */
  private fun findUserType(propertyType: KClass<*>): UserType? {
    return when (propertyType) {
      Id::class -> IdType()
      ByteString::class -> ByteStringType
      else -> null
    }
  }

  private fun kClassForName(name: String): KClass<out Any> {
    return when (name) {
      "boolean" -> Boolean::class
      "byte" -> Byte::class
      "short" -> Short::class
      "int" -> Int::class
      "long" -> Long::class
      "char" -> Char::class
      "float" -> Float::class
      "double" -> Double::class
      "materialized_clob" -> String::class
      else -> Class.forName(name).kotlin
    }
  }

  override fun shutDown() {
    val stopwatch = Stopwatch.createStarted()
    logger.info("Stopping @${qualifier.simpleName} Hibernate")

    require(sessionFactory != null)
    sessionFactory!!.close()

    logger.info("Stopped @${qualifier.simpleName} Hibernate in $stopwatch")
  }

  override fun get(): SessionFactory {
    return sessionFactory ?: throw IllegalStateException("""
      |@${qualifier.simpleName} Hibernate not connected: did you forget to start the service?
      |    If this is a test, then annotate your test class with @MiskTest(startService = true)
      |""".trimMargin())
  }
}
