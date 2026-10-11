package dev.minecraft;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.network.NetworkState;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * A network connection that goes nowhere. Packets "sent to the client" are dropped, except chat,
 * which is handed to the bot's brain. This is what lets the server treat the bot as a connected player.
 */
public class FakeConnection extends ClientConnection {
	private final AiPlayer owner;
	private PacketListener listener;
	private DisconnectionInfo info;
	private boolean closed;

	public FakeConnection(AiPlayer owner) {
		super(NetworkSide.SERVERBOUND);
		this.owner = owner;
	}

	private void intercept(Packet<?> packet, @Nullable PacketCallbacks callbacks) {
		if (packet instanceof ChatMessageS2CPacket chat) owner.onChatPacket(chat);
		else if (packet instanceof ProfilelessChatMessageS2CPacket chat) owner.onChatPacket(chat);
		// Let "sent" callbacks run, e.g. the disconnect that follows a kick packet.
		if (callbacks != null) callbacks.onSuccess();
	}

	@Override
	public void send(Packet<?> packet) {
		intercept(packet, null);
	}

	@Override
	public void send(Packet<?> packet, @Nullable PacketCallbacks callbacks) {
		intercept(packet, callbacks);
	}

	@Override
	public void send(Packet<?> packet, @Nullable PacketCallbacks callbacks, boolean flush) {
		intercept(packet, callbacks);
	}

	@Override
	public boolean isOpen() {
		return !closed;
	}

	@Override
	public void tick() {
	}

	@Override
	public void flush() {
		// there is no channel to flush
	}

	@Override
	public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {
		this.listener = packetListener;
	}

	@Override
	public void transitionOutbound(NetworkState<?> state) {
	}

	@Override
	public void tryDisableAutoRead() {
	}

	@Override
	public void disconnect(Text reason) {
		disconnect(new DisconnectionInfo(reason));
	}

	@Override
	public void disconnect(DisconnectionInfo info) {
		if (this.info == null) this.info = info;
	}

	@Override
	public void handleDisconnection() {
		if (closed) return;
		closed = true;
		if (listener != null) {
			listener.onDisconnected(info != null ? info : new DisconnectionInfo(Text.literal("Disconnected")));
		}
	}
}
