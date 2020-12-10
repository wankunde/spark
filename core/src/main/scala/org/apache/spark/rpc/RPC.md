# Spark RPC vs Akka

RpcEndpoint => Actor
RpcEndpointRef => ActorRef
RpcEnv => ActorSystem

## RpcEnv

* 实现类是NettyRpcEnv，由NettyRpcEnvFactory.create创建。
* 通过名字或 URI 注册 RpcEndpoint。
* 对收到的消息进行路由，决定分发给哪个 RpcEndpoint。
* 停止 RpcEndpoint。

## RpcEndpoint

* 服务器处理消息主程序，继承 RpcEndpoint实现消息处理逻辑
* 生命周期被RpcEnv管理，包括 onStart, receive和 onStop阶段

## RpcEndpointRef

* RpcEndpointRef是RpcEndpoint在RpcEnv中的一个引用。
* 它包含一个地址（即Spark URL）和名字。
* RpcEndpointRef作为客户端向服务端发送请求并接收返回信息，通常可以选择使用同步或异步的方式进行发送。

## Dispatcher

* Dispatcher的主要作用是保存注册的RpcEndpoint、分发相应的Message到RpcEndPoint中进行处理

## Inbox

* inboxMessage有多个实现它的类，比如OneWayMessage，RpcMessage，等等。
* Dispatcher会将接收到的InboxMessage分发到对应RpcEndpoint的Inbox中，然后Inbox便会处理这个InboxMessage。

# RPC流程

## RPC Server setup and register endpoint

```
RpcEnv.create()
new NettyRpcEnvFactory().create(config)
nettyEnv.startServer(config.bindAddress, actualPort)
transportContext.createServer(bindAddress, port, bootstraps)

// netty server
new TransportServer(this, host, port, rpcHandler, bootstraps);
    init(hostToBind, portToBind);
        context.initializePipeline(ch, rpcHandler);
```

setup endpoint
```
NettyRpcEnv.setupEndpoint(name: String, endpoint: RpcEndpoint)
dispatcher.registerRpcEndpoint(name, endpoint)
```


## Netty发送和接收数据Handlers

```
TransportContext initializePipeline

ENCODER: 将输入的消息封装为Frame（MessageWithHeader）进行发送，包含header和body两部分

TransportFrameDecoder: 读取frame数据。先解析bodyLength: Long,再读取完整body数据向后传递
DECODER: 先读取msgType: Byte,再根据msgType decode出对应消息对象

IdleStateHandler: Netty自带的存活检测
TransportChannelHandler: 将消息转发给内部的 requestHandler 和 responseHandler 
```
requestHandler 处理请求消息

* RpcRequest
* OneWayMessage
* StreamRequest
* UploadStream
* ChunkFetchRequest

responseHandler 处理请求返回的消息

* RpcResponse
* RpcFailure
* ChunkFetchSuccess
* ChunkFetchFailure
* StreamResponse
* StreamFailure
    
## RPC消息处理

上面的 RpcRequest 和 OneWayMessage 实际是调用了RpcHandler来进行消息处理

```
NettyRpcHandler.receive()
  internalReceive(client, message) 把传递进来的消息体和env，client 一起重新封装为 RequestMessage 对象
    RequestMessage(nettyEnv, client, message)
Dispatcher.postRemoteMessage()
MessageLoop.postRemoteMessage() 把上面的RequestMessage解包，重新封装为 RpcMessage转发
  postMessage()
SharedMessageLoop.post(endpointName, message) 将消息投递到 endpointRef 对应的 inbox
Inbox.process(dispatcher) inbox异步消费消息
    RpcMessage -> endpoint.receiveAndReply(context) 通过context.reply() -> context.send()方法，返回结果。将返回消息序列化后调用 callback.onSuccess(reply) 方法返回。 
    OneWayMessage -> endpoint.receive
    OnStart -> endpoint.onStart()
    OnStop -> endpoint.onStop()
    RemoteProcessConnected -> endpoint.onConnected(remoteAddress)
    RemoteProcessDisconnected -> endpoint.onDisconnected(remoteAddress)
    RemoteProcessConnectionError -> endpoint.onNetworkError(cause, remoteAddress)
```

## RpcClient 发送消息

NettyRpcEndpointRef : 将消息封装为 RequestMessage，再进行序列化，再将消息封装为对应的消息类型
  send() -> OneWayOutboxMessage
  ask() -> RpcOutboxMessage
  
  如果是本地消息，dispatcher.postLocalMessage
  否则 将消息投递到outbox postToOutbox(message.receiver, OneWayOutboxMessage(message.serialize(this)))

Outbox.send(message) 消息发送到outbox队列 
Outbox.drainOutbox() outbox消费消息 
  launchConnectTask() 和endpointRef 建立连接
    nettyEnv.createClient(address) 建立连接并对绑定outbox对应的client对象
  message.sendWith(_client) 通过 client 发送message对象，RpcOutboxMessage 会返回requestId， OneWayOutboxMessage无返回值
    this.requestId = client.sendRpc(content, this) 
  
## TransportClient 建立和消息发送


### client建立

```
NettyRpcEnv.createClient(address: RpcAddress)
  TransportClientFactory.createClient(String remoteHost, int remotePort) netty 创建客户端连接
    TransportChannelHandler clientHandler = context.initializePipeline(ch) 
    根据netty channel连接建立对应的handler，下面的代码主要还是各种handler的创建， 参考 **Netty发送和接收数据Handlers** 部分
      TransportChannelHandler channelHandler = createChannelHandler(channel, channelRpcHandler);
        TransportResponseHandler responseHandler = new TransportResponseHandler(channel);
        TransportClient client = new TransportClient(channel, responseHandler); 返回 TransportChannelHandler 内部包装的client对象
```

### 消息发送
TransportClient.sendRpc()
  handler.addRpcRequest(requestId, callback);  每个RPC都有一个 requestId，接收RPC返回结果时，根据ID调用对应的callback 
  channel.writeAndFlush(new RpcRequest(requestId, new NioManagedBuffer(message))) 向channel发送 RpcRequest
        .addListener(listener); 如果发送失败，会调用callback的 onFailure 方法