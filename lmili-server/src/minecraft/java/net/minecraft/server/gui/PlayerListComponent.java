package net.minecraft.server.gui;

import java.util.Vector;
import javax.swing.JList;
import net.minecraft.server.MinecraftServer;

public class PlayerListComponent extends JList<String> {
    private final MinecraftServer server;
    private int tickCount;

    public PlayerListComponent(final MinecraftServer server) {
        this.server = server;
        server.addTickable(this::tick);
    }

    public void tick() {
        if (this.tickCount++ % 20 == 0) {
            Vector<String> players = new Vector<>();

            for (int i = 0; i < this.server.getPlayerList().realPlayers.size(); i++) { // Leaves - only real players
                players.add(this.server.getPlayerList().realPlayers.get(i).getGameProfile().name()); // Leaves - only real players
            }

            this.setListData(players);
        }
    }
}
