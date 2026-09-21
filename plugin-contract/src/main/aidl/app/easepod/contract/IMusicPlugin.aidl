package app.easepod.contract;
import app.easepod.contract.HostHello;
import app.easepod.contract.RequestEnvelope;
import app.easepod.contract.IHandshakeCallback;
import app.easepod.contract.IResultCallback;
interface IMusicPlugin {
    oneway void handshake(in HostHello hello, IHandshakeCallback callback);
    oneway void execute(in RequestEnvelope request, IResultCallback callback);
    oneway void cancel(String connectionId, String requestId);
}
