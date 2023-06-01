# HTTP Client v0.9.0

This microservice allows performing HTTP requests and receive HTTP responses. It also can perform basic authentication

## Configuration

Main configuration is done via setting following properties in a custom configuration:

+ **https** - enables HTTPS (`false` by default)
+ **host** - host for HTTP requests (e.g. `google.com`)
+ **port** - port for HTTP requests (`80` by default or `443` if `https` = `true`)
+ **readTimeout** - socket read timeout in ms (`5000` by default)
+ **keepAliveTimeout** - socket inactivity timeout in ms (`15000` by default)
+ **validateCertificates** - enables/disables server certificate validation (`true` by default)
+ **clientCertificate** - path to client X.509 certificate in PEM format (requires `certificatePrivateKey`, `null` by
  default)
+ **certificatePrivateKey** - path to client certificate RSA private key (PKCS8 encoded) in PEM format (`null` by
  default)
+ **defaultHeaders** - map of default headers, and their values which will be applied to each request (existing headers
  are not affected, empty by default)
+ **sessionAlias** - session alias for incoming/outgoing TH2 messages (e.g. `rest_api`)
+ **auth** - basic authentication settings (`null` by default)
+ **useTransport** - use th2 transport or protobuf protocol to publish incoming/outgoing messages (`false` by default)
+ **batchByGroup** - batch messages by group instead of session alias and direction (`true` by default)
+ **batcherThreads** - amount of event/message batcher threads (`2` by default)
+ **maxBatchSize** - max size of outgoing message batch (`1000` by default)
+ **maxFlushTime** - max message batch flush time (`1000` by default)

### Authentication configuration

Basic authentication can be configured via setting following properties in the `auth` block of the main configuration

+ **username** - basic authentication username
+ **password** - basic authentication password

### Configuration example

```yaml
https: false
host: someapi.com
port: 334
sessionAlias: api_session
readTimeout: 5000
keepAliveTimeout: 15000
validateCertificates: true
clientCertificate: /path/to/certificate
certificatePrivateKey: /path/to/certificate/private/key
defaultHeaders:
  x-api-key: [ 'apikeywashere' ]
auth:
  username: coolusername
  password: verystrongpassword
```

### MQ pins

#### input pins

* pin with `subscribe` and `send` attributes for requests using protobuf
* pin with `subscribe`, `send` and `transport-group` attributes for requests using th2 transport

Note: least one of pins above is required, it's mean that conn can handle messages via one or both protocols at the same
time

#### output pins

Required output pins set depends on values of the `useTransport` and `batchByGroup` options

`useTransport` option is `true` and `batchByGroup` option is `true`

* output queue with `publish`, `transport-group` attributes for transport group batch with responses and requests

`useTransport` option is `true` and `batchByGroup` option is `false`

* output queue with `publish`, `transport-group` and `first` (for responses) and `second` (for requests) attributes

`useTransport` option is `false` and `batchByGroup` option is `true`

* output queue with `publish`, `raw` attributes for transport group batch with responses and requests

`useTransport` option is `false` and `batchByGroup` option is `false`

* output queue with `publish`, `raw` and `first` (for responses) and `second` (for requests) attributes

## Inputs/outputs

This section describes messages received and by produced by the service

### Inputs

This service receives HTTP requests via MQ as `transport message group`s containing one of:

* a single `raw message` containing request body, which can have `uri`, `method`, and `contentType` properties in its
  metadata, which will be used in resulting request
* a single parsed message with `Request` message type containing HTTP request line and headers and a `RawMessage`
  described
  above

If both `parsed` and `raw` messages contain `uri`, `method`, and `contentType`, values from `parsed` message take
precedence.  
If none of them contain these values `/` and `GET` will be used as `uri` and `method` values respectively

#### Message descriptions

* Request

|  Field  |     Type      |                             Description                              |
|:-------:|:-------------:|:--------------------------------------------------------------------:|
| method  |    String     |               HTTP method name (e.g. GET, POST, etc.)                |
|   uri   |    String     | Request URI (e.g. /some/request/path?param1=value1&param2=value2...) |
| headers | List\<Header> |            HTTP headers (e.g. Host, Content-Length, etc.)            |

* Header

| Field |  Type  |    Description    |
|:-----:|:------:|:-----------------:|
| name  | String | HTTP header name  |
| value | String | HTTP header value |

Metadata properties prefixed with `header-` (case insensitive) are put in the HTTP request as headers with `header-`
prefix removed

### Outputs

HTTP requests and responses are sent via MQ as `transport message group`s containing a single `raw` message with a raw
request or
response.   
`raw` message also has `uri`, `method`, and `contentType` metadata properties set equal to URI, method, and content type
of request (or a response received for the request). In case of response request metadata properties and parent event id
are copied into response message.

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: http-client
spec:
  image-name: ghcr.io/th2-net/th2-conn-http-client
  image-version: 0.7.2
  custom-config:
    https: false
    host: 127.0.0.1
    port: 8080
    sessionAlias: some_api
    readTimeout: 5000
    keepAliveTimeout: 15000
    validateCertificates: true
    clientCertificate: /secret/storage/cert.crt
    certificatePrivateKey: /secret/storage/private.key
    useTransport: false
    batchByGroup: true
    batcherThreads: 2
    maxBatchSize: 1000
    maxFlushTime: 1000
    defaultHeaders:
      x-api-key: [ 'apikeywashere' ]
    auth:
      username: user
      password: pwds
  type: th2-conn
  pins:
    - name: to_send_protobuf
      connection-type: mq
      attributes:
        - subscribe
        - send
        - group
    - name: to_send_transport
      connection-type: mq
      attributes:
        - subscribe
        - send
        - transport-group
    - name: out_group_request
      connection-type: mq
      attributes:
        - publish
        - raw
  extended-settings:
    service:
      enabled: false
```

## Changelog

### v0.9.0

* Supports th2 transport protocol
* Use event / message batcher
    * Support batching by session group or session alias + direction

* kotlin upgrade to `1.6.21`
* owasp upgrade to `8.2.1`
* th2-bom upgrade to `4.2.0`
* th2-common upgrade to `5.3.0`

### v0.8.0

* Vulnerability check pipeline step
* th2-common upgrade to `3.44.0`
* th2-bom upgrade to `4.1.0`

### v0.7.2

#### Added:

* Publish `Failed to handle message group` event for each message in group with present parent event id

### v0.7.1

#### Changed:

* Bump `com.exactpro.th2:common` dependency to `3.41.1`

### v0.7.0

#### Added:

* Ability to pass headers via message metadata

### v0.6.0

#### Future:

* Ability to send multiple requests in parallel

### v0.5.3

#### Fixed:

* Convert method from incoming message group to uppercase for better compatibility with some servers

### v0.5.2

#### Fixed:

* fixed bug with not sending all requests with body

### v0.5.1

#### Fixed:

* fixed bug with parent id loss inside client after sending request

### v0.5.0

#### Added:

* publish "sent" events for outgoing messages (requests)

### v0.4.0

#### Added:

* support for client-side certificate

### v0.3.0

#### Added:

* option to disable server certificate validation

### v0.2.0

#### Added:

* copying of metadata properties from request into response

### v0.1.2

#### Fixed:

* fixed bug with 'name: name' headers

### v0.1.1

#### Fixed:

* potential endless-loop if `onStart` handler tries to send something

### v0.1.0

#### Added:

* start/stop API to internal HTTP client

#### Updated:

* dependencies

### v0.0.15

#### Added:

* removal of socket in case of network error

### v0.0.13

#### Changed:

* response message will be with same parent event id as request message batch

### v0.0.10

#### Fixed:

* high idle CPU usage

### v0.0.9

#### Fixed:

* expired sockets weren't removed from the cache

### v0.0.8

#### Changed:

* use separate sequences for request and response message streams
* don't overwrite `Content-Length` header if it's already present

### v0.0.7

#### Fixed:

* invalid error message in case of unexpected incoming message type
