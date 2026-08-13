package net.minecraft.server.network.config;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.server.network.ConfigurationTask;

public class ServerCodeOfConductConfigurationTask implements ConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("server_code_of_conduct");
    private final Supplier<String> codeOfConduct;

    public ServerCodeOfConductConfigurationTask(final Supplier<String> codeOfConduct) {
        this.codeOfConduct = codeOfConduct;
    }

    @Override
    public void start(final Consumer<Packet<?>> connection) {
        // Paper start - Code of Conduct event
        // Null code of conduct cancels the send
        // See ServerConfigurationPacketListenerImpl section where the task is immediately completed when a null is sent here
        String codeOC = this.codeOfConduct.get();
        if (codeOC == null) {
            return;
        }
        connection.accept(new ClientboundCodeOfConductPacket(codeOC));
        // Paper end - Code of Conduct event
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
