# HTTP Client v1.0.0

This microservice allows performing HTTP requests and receive HTTP responses. It also can perform basic authentication
Http client was created based on dirty tcp core and don't have a mangler implementation. 

### Configuration example

```yaml
handler:
  defaultHeaders:
    'Content-type': 'application/json'
```

### MQ pins

* input queue with `subscribe` and `send` attributes for requests
* output queue with `publish` and `first` (for responses) and `second` (for requests) attributes

## Inputs/outputs

This section describes messages received and by produced by the service

### Inputs

This service receives HTTP requests via MQ as `MessageGroup`s containing:

* a single `RawMessage` containing serialized request

### Outputs

HTTP requests and responses are sent via MQ as `MessageGroup`s containing a single `RawMessage` with a raw request or response.   
`RawMessage` also has `uri`, `method`, and `contentType` metadata properties set equal to URI, method, and content type of request (or a response received for the request). In case of response request metadata properties and parent event id
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
  image-version: 1.0.0
  custom-config:
    autoStart: false
    autoStopAfter: 0
    totalThreads: 2
    ioThreads: 1
    maxBatchSize: 100
    maxFlushTime: 3000
    reconnectDelay: 5000
    publishSentEvents: true
    sessions: 
      - secure: false
        host: localhost
        port: 80
        sessionAlias: alias-test
        handler:
          defaultHeaders:
            'Content-type': [ 'application/json' ]
    defaultHeaders:
      x-api-key: [ 'apikeywashere' ]
    auth:
      username: user
      password: pwds
  type: th2-conn
  pins:
    - name: to_send
      connection-type: mq
      attributes:
        - subscribe
        - send
        - group
    - name: out_request
      connection-type: mq
      attributes:
        - publish
        - second
        - raw
    - name: out_response
      connection-type: mq
      attributes:
        - publish
        - first
        - raw
  extended-settings:
    service:
      enabled: false
```

## Changelog

### v1.0.0

#### Future:

* Replaced RawHttpCore with DirtyTcpCore

### v0.6.1

#### Future:

* Error message due closed socket while processing response

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
