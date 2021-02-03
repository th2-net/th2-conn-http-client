# HTTP Client

This microservice allows performing HTTP requests and receive HTTP responses. It also can perform basic authentication

## Configuration

Main configuration is done via setting following properties in a custom configuration:

+ **https** - enables HTTPS (`false` by default)
+ **host** - host for HTTP requests (e.g. `google.com`)
+ **port** - port for HTTP requests (`80` by default or `443` if `https` = `true`)
+ **sessionAlias** - session alias for incoming/outgoing TH2 messages (e.g. `rest_api`)
+ **defaultHeaders** - map of default headers, and their values which will be applied to each request (existing headers are not affected, empty by default)
+ **auth** - basic authentication settings (empty by default)

### Authentication configuration

Basic authentication can configured via settings following properties in the `auth` block of the main configuration

+ **username** - basic authentication username
+ **password** - basic authentication password

### Configuration example

```yaml
https: false
host: someapi.com
port: 334
sessionAlias: api_session
auth:
  username: coolusername
  password: verystrongpassword
```

### MQ pins

* input queue with `subscribe` and `send` attributes for requests
* output queue with `publish` and `first` (for responses) and `second` (for requests) attributes

## Inputs/outputs

This section describes messages received and by produced by the service

### Inputs

This service receives HTTP requests via MQ as `MessageGroup`s containing one of:

* a single `RawMessage` containing request body, which can have `uri` and `method` properties in its metadata, which will be used in resulting request
* a single `Message` with `Request` message type containing HTTP request line and headers and a `RawMessage` described above

If both `Message` and `RawMessage` contain `uri` and `method` values from `Message` take precedence.  
If none of them contain these values `/` and `GET` will be used as `uri` and `method` values respectively

#### Message descriptions

* Request

|Field|Type|Description|
|:---:|:---:|:---:|
|method|String|HTTP method name (e.g. GET, POST, etc.)|
|uri|String|Request URI (e.g. /some/request/path?param1=value1&param2=value2...)|
|headers|List\<Header>|HTTP headers (e.g. Host, Content-Length, etc.)|

* Header

|Field|Type|Description|
|:---:|:---:|:---:|
|name|String|HTTP header name|
|value|String|HTTP header value|

### Outputs

HTTP requests and responses are sent via MQ as `MessageGroup`s containing a single `RawMessage` with a raw request or response.   
`RawMessage` also has `uri` and `method` properties set equal to URI and method of a request (or a response received for the request)

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: http-client
spec:
  image-name: nexus.exactpro.com:18000/th2-http-client
  image-version: 0.0.2
  custom-config:
    https: false
    host: 127.0.0.1
    port: 8080
    sessionAlias: some_api
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
```