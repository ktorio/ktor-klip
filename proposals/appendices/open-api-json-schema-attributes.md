# JSON Schema Attributes Reference Table

The following tables provide a list of all JSON schema fields that will pass through our OpenAPI KDoc generator to supplement parameter schema.

## Number/Integer Validation

| Parameter          | Description                                     | Example                 |
|--------------------|-------------------------------------------------|-------------------------|
| `minimum`          | Specifies the minimum allowed value (inclusive) | `minimum: 0`            |
| `maximum`          | Specifies the maximum allowed value (inclusive) | `maximum: 100`          |
| `exclusiveMinimum` | Specifies the exclusive minimum value           | `exclusiveMinimum: 0`   |
| `exclusiveMaximum` | Specifies the exclusive maximum value           | `exclusiveMaximum: 100` |
| `multipleOf`       | Value must be a multiple of this number         | `multipleOf: 5`         |

## String Validation

| Parameter   | Description                  | Example                     |
|-------------|------------------------------|-----------------------------|
| `minLength` | Minimum string length        | `minLength: 3`              |
| `maxLength` | Maximum string length        | `maxLength: 50`             |
| `pattern`   | Regex pattern for validation | `pattern: "^[A-Za-z0-9]+$"` |
| `format`    | Predefined format validation | `format: email`             |

## Array Validation

| Parameter     | Description                                   | Example                    |
|---------------|-----------------------------------------------|----------------------------|
| `minItems`    | Minimum number of items                       | `minItems: 1`              |
| `maxItems`    | Maximum number of items                       | `maxItems: 10`             |
| `uniqueItems` | Whether items must be unique                  | `uniqueItems: true`        |
| `contains`    | Array must contain at least one matching item | `contains: {type: string}` |
| `minContains` | Minimum items that must match `contains`      | `minContains: 2`           |
| `maxContains` | Maximum items that can match `contains`       | `maxContains: 5`           |

## Object Validation

| Parameter              | Description                                 | Example                                          |
|------------------------|---------------------------------------------|--------------------------------------------------|
| `required`             | List of required properties                 | `required: [name, email]`                        |
| `minProperties`        | Minimum number of properties                | `minProperties: 1`                               |
| `maxProperties`        | Maximum number of properties                | `maxProperties: 10`                              |
| `propertyNames`        | Schema for validating property names        | `propertyNames: {pattern: "^[A-Za-z]+$"}`        |
| `additionalProperties` | Controls additional properties              | `additionalProperties: false`                    |
| `patternProperties`    | Schema for property names matching patterns | `patternProperties: {"^S_": {type: string}}`     |
| `dependencies`         | Property dependencies                       | `dependencies: {credit_card: [billing_address]}` |

## Conditional Validation

| Parameter           | Description                    | Example                                                        |
|---------------------|--------------------------------|----------------------------------------------------------------|
| `if`                | Conditional schema             | `if: {properties: {type: {enum: [residential]}}}`              |
| `then`              | Schema to apply if `if` passes | `then: {required: [address]}`                                  |
| `else`              | Schema to apply if `if` fails  | `else: {required: [business_name]}`                            |
| `dependentRequired` | Property dependencies          | `dependentRequired: {foo: [bar]}`                              |
| `dependentSchemas`  | Schema dependencies            | `dependentSchemas: {foo: {properties: {bar: {type: string}}}}` |

## Combining Schemas

| Parameter | Description                                    | Example                                   |
|-----------|------------------------------------------------|-------------------------------------------|
| `allOf`   | Data must be valid against all schemas         | `allOf: [{type: string}, {maxLength: 5}]` |
| `anyOf`   | Data must be valid against at least one schema | `anyOf: [{type: string}, {type: number}]` |
| `oneOf`   | Data must be valid against exactly one schema  | `oneOf: [{type: string}, {type: number}]` |
| `not`     | Data must not be valid against the schema      | `not: {type: string}`                     |

## General Validation

| Parameter  | Description                         | Example                          |
|------------|-------------------------------------|----------------------------------|
| `type`     | Specifies the data type             | `type: string`                   |
| `enum`     | List of allowed values              | `enum: [red, green, blue]`       |
| `const`    | Exact value match                   | `const: example`                 |
| `default`  | Default value                       | `default: example`               |
| `examples` | Example values (not for validation) | `examples: [example1, example2]` |

## Metadata

| Parameter     | Description                      | Example                                     |
|---------------|----------------------------------|---------------------------------------------|
| `title`       | Title of the schema              | `title: User Profile`                       |
| `description` | Description of the schema        | `description: A user profile schema`        |
| `$comment`    | Comments about the schema        | `$comment: Additional implementation notes` |
| `readOnly`    | Indicates read-only status       | `readOnly: true`                            |
| `writeOnly`   | Indicates write-only status      | `writeOnly: true`                           |