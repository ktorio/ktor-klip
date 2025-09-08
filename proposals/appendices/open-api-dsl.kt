import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import io.ktor.http.*
import io.ktor.utils.io.KtorDsl

fun Route.annotate(configure: Operation.Builder.() -> Unit): Route {
    attributes[Operation.AttributeKey] = Operation.Builder()
        .also { it.configure() }
        .build()
    return this
}

data class Operation(
    val tags: List<String>?,
    val summary: String?,
    val description: String?,
    val externalDocs: String?,
    val operationId: String?,
    val parameters: List<Parameter>?,
    val requestBody: RequestBody?,
    val responses: Map<String, Response>?,
    val deprecated: Boolean?,
    val security: List<Map<String, List<String>>>?,
    val servers: List<Server>?,
    val extensions: Map<String, Any>?
) {
    companion object {
        val AttributeKey = AttributeKey<Operation>("operation-docs")
    }

    /**
     * Builder for OpenAPI Operation object
     */
    @KtorDsl
    class Builder {
        // Basic metadata fields
        var summary: String? = null
        var description: String? = null
        var operationId: String? = null
        var deprecated: Boolean = false
        var externalDocs: String? = null

        // Collections
        private val _tags = mutableListOf<String>()
        private val _parameters = mutableListOf<Parameter>()
        private val _responses = mutableMapOf<String, Response>()
        private val _servers = mutableListOf<Server>()
        private val _securityRequirements = mutableListOf<Map<String, List<String>>>()

        val tags: List<String> get() = _tags
        val parameters: List<Parameter> get() = _parameters
        val responses: Map<String, Response> get() = _responses
        val servers: List<Server> get() = _servers
        val security: List<Map<String, List<String>>> get() = _securityRequirements

        // Request body
        var requestBody: RequestBody? = null

        // Extensions
        private val _extensions = mutableMapOf<String, Any>()
        val extensions: Map<String, Any> get() = _extensions

        //  methods for collections
        fun tag(tag: String) {
            _tags.add(tag)
        }

        fun parameters(configure: Parameters.Builder.() -> Unit) {
            Parameters.Builder().apply(configure).build().parameters.forEach { _parameters.add(it) }
        }

        fun requestBody(configure: RequestBody.Builder.() -> Unit) {
            requestBody = RequestBody.Builder().apply(configure).build()
        }

        fun responses(configure: Responses.Builder.() -> Unit) {
            Responses.Builder().apply(configure).build().responses.forEach { (code, response) ->
                _responses[code] = response
            }
        }

        fun servers(configure: Servers.Builder.() -> Unit) {
            Servers.Builder().apply(configure).build().servers.forEach { _servers.add(it) }
        }

        fun security(configure: Security.Builder.() -> Unit) {
            Security.Builder().apply(configure).build().requirements.forEach { _securityRequirements.add(it) }
        }

        fun extension(name: String, value: Any) {
            require(name.startsWith("x-")) { "Extension name must start with 'x-'" }
            _extensions[name] = value
        }

        internal fun build(): Operation {
            return Operation(
                tags = if (_tags.isEmpty()) null else _tags,
                summary = summary,
                description = description,
                externalDocs = externalDocs,
                operationId = operationId,
                parameters = if (_parameters.isEmpty()) null else _parameters,
                requestBody = requestBody,
                responses = _responses,
                deprecated = deprecated,
                security = if (_securityRequirements.isEmpty()) null else _securityRequirements,
                servers = if (_servers.isEmpty()) null else _servers,
                extensions = _extensions
            )
        }
    }
}

// Parameters 
data class Parameters(
    val parameters: List<Parameter>
) {
    @KtorDsl
    class Builder {
        private val _parameters = mutableListOf<Parameter>()
        val parameters: List<Parameter> get() = _parameters

        fun parameter(configure: Parameter.Builder.() -> Unit) {
            _parameters.add(Parameter.Builder().apply(configure).build())
        }

        // Shorthand methods for common parameter types
        fun path(name: String, configure: Parameter.Builder.() -> Unit = {}) {
            Parameter.Builder().apply {
                this.name = name
                this.`in` = "path"
                this.required = true  // Path parameters are always required
                configure()
            }.also { _parameters.add(it.build()) }
        }

        fun query(name: String, configure: Parameter.Builder.() -> Unit = {}) {
            Parameter.Builder().apply {
                this.name = name
                this.`in` = "query"
                configure()
            }.also { _parameters.add(it.build()) }
        }

        fun header(name: String, configure: Parameter.Builder.() -> Unit = {}) {
            Parameter.Builder().apply {
                this.name = name
                this.`in` = "header"
                configure()
            }.also { _parameters.add(it.build()) }
        }

        fun cookie(name: String, configure: Parameter.Builder.() -> Unit = {}) {
            Parameter.Builder().apply {
                this.name = name
                this.`in` = "cookie"
                configure()
            }.also { _parameters.add(it.build()) }
        }

        internal fun build(): Parameters {
            return Parameters(_parameters)
        }
    }
}

// Parameter 
data class Parameter(
    val name: String,
    val `in`: String,  // "path", "query", "header", "cookie"
    val description: String?,
    val required: Boolean,
    val deprecated: Boolean,
    val schema: JsonSchema?,
    val extensions: Map<String, Any>?
) {
    @KtorDsl
    class Builder {
        var name: String? = null
        var `in`: String? = null  // "path", "query", "header", "cookie"
        var description: String? = null
        var required: Boolean = false
        var deprecated: Boolean = false
        var schema: JsonSchema? = null
        private val _extensions = mutableMapOf<String, Any>()

        inline fun <reified T> schema() {
            schema = jsonSchema<T>()
        }

        fun extension(name: String, value: Any) {
            require(name.startsWith("x-")) { "Extension name must start with 'x-'" }
            _extensions[name] = value
        }

        internal fun build(): Parameter {
            requireNotNull(name) { "Parameter name is required" }
            requireNotNull(`in`) { "Parameter location ('in') is required" }

            return Parameter(
                name = name!!,
                `in` = `in`!!,
                description = description,
                required = required,
                deprecated = deprecated,
                schema = schema,
                extensions = _extensions
            )
        }
    }
}

// Responses 
data class Responses(
    val responses: Map<String, Response>
) {
    @KtorDsl
    class Builder {
        private val _responses = mutableMapOf<String, Response>()
        val responses: Map<String, Response> get() = _responses

        fun response(statusCode: String, configure: Response.Builder.() -> Unit) {
            _responses[statusCode] = Response.Builder().apply(configure).build()
        }

        // Shorthand method for HTTP status code responses
        operator fun HttpStatusCode.invoke(configure: Response.Builder.() -> Unit = {}) {
            response(this.toString(), configure)
        }

        // Default response
        fun default(configure: Response.Builder.() -> Unit) {
            response("default", configure)
        }

        internal fun build(): Responses {
            return Responses(_responses)
        }
    }
}

// Response 
data class Response(
    val description: String,
    val headers: Map<String, Parameter>?,
    val extensions: Map<String, Any>?
) {
    @KtorDsl
    class Builder {
        var description: String = ""  // Required by OpenAPI spec
        var contentType: ContentType? = null
        var schema: JsonSchema? = null
        private val _headers = mutableMapOf<String, Parameter>()
        private val _extensions = mutableMapOf<String, Any>()

        fun headers(configure: Headers.Builder.() -> Unit) {
            Headers.Builder().apply(configure).build().headers.forEach { (name, header) ->
                _headers[name] = header
            }
        }

        fun extension(name: String, value: Any) {
            require(name.startsWith("x-")) { "Extension name must start with 'x-'" }
            _extensions[name] = value
        }

        internal fun build(): Response {
            return Response(
                description = description,
                headers = _headers.ifEmpty { null },
                extensions = _extensions
            )
        }
    }
}

// Request Body 
data class RequestBody(
    val description: String?,
    val contentType: ContentType?,
    val schema: JsonSchema?,
    val required: Boolean,
    val extensions: Map<String, Any>?
) {
    @KtorDsl
    class Builder {
        var description: String? = null
        var required: Boolean = false
        var contentType: ContentType? = null
        var schema: JsonSchema? = null
        private val _extensions = mutableMapOf<String, Any>()

        internal fun build(): RequestBody {
            return RequestBody(
                description = description,
                contentType = contentType,
                schema = schema,
                required = required,
                extensions = _extensions
            )
        }
    }
}

// Security 
data class Security(
    val requirements: List<Map<String, List<String>>>
) {
    @KtorDsl
    class Builder {
        private val _requirements = mutableListOf<Map<String, List<String>>>()
        val requirements: List<Map<String, List<String>>> get() = _requirements

        fun requirement(scheme: String, scopes: List<String> = emptyList()) {
            _requirements.add(mapOf(scheme to scopes))
        }

        // Common security schemes
        fun basic() {
            requirement("basicAuth")
        }

        fun apiKey(name: String) {
            requirement(name)
        }

        fun oauth2(vararg scopes: String) {
            requirement("oauth2", scopes.toList())
        }

        fun openIdConnect(vararg scopes: String) {
            requirement("openIdConnect", scopes.toList())
        }

        internal fun build(): Security {
            return Security(_requirements)
        }
    }
}

// Servers 
data class Servers(
    val servers: List<Server>
) {
    @KtorDsl
    class Builder {
        private val _servers = mutableListOf<Server>()
        val servers: List<Server> get() = _servers

        fun server(url: String, configure: Server.Builder.() -> Unit = {}) {
            _servers.add(Server.Builder().apply {
                this.url = url
                configure()
            }.build())
        }

        internal fun build(): Servers {
            return Servers(_servers)
        }
    }
}

// Server 
data class Server(
    val url: String,
    val description: String?,
    val extensions: Map<String, Any>?
) {
    class Builder {
        var url: String? = null
        var description: String? = null
        private val _extensions = mutableMapOf<String, Any>()

        fun extension(name: String, value: Any) {
            require(name.startsWith("x-")) { "Extension name must start with 'x-'" }
            _extensions[name] = value
        }

        internal fun build(): Server {
            requireNotNull(url) { "Server URL is required" }

            return Server(
                url = url!!,
                description = description,
                extensions = _extensions
            )
        }
    }
}

// Additional needed s
data class Headers(
    val headers: Map<String, Parameter>
) {
    @KtorDsl
    class Builder {
        private val _headers = mutableMapOf<String, Parameter>()
        val headers: Map<String, Parameter> get() = _headers

        fun header(name: String, configure: Parameter.Builder.() -> Unit) {
            _headers[name] = Parameter.Builder().apply(configure).build()
        }

        internal fun build(): Headers {
            return Headers(_headers)
        }
    }
}

// Remaining required s (assuming they exist elsewhere or need to be defined)
interface JsonSchema

// Helper function to create a JSON schema from a type parameter
inline fun <reified T> jsonSchema(): JsonSchema =
    TODO() // Simplified for this example