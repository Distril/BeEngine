package org.distril.beengine.player;

import com.nukkitx.math.vector.Vector2f;
import com.nukkitx.math.vector.Vector3i;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockServerSession;
import com.nukkitx.protocol.bedrock.data.*;
import com.nukkitx.protocol.bedrock.packet.*;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.distril.beengine.entity.EntityHuman;
import org.distril.beengine.network.Network;
import org.distril.beengine.network.data.LoginData;
import org.distril.beengine.player.data.Gamemode;
import org.distril.beengine.player.data.PlayerData;
import org.distril.beengine.player.data.PlayerList;
import org.distril.beengine.server.Server;
import org.distril.beengine.util.BedrockResourceLoader;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Log4j2
public class Player extends EntityHuman {

	private final Server server;
	private final BedrockServerSession session;
	private final LoginData loginData;

	private final PlayerList playerList = new PlayerList(this);
	private PlayerData data;

	public Player(Server server, BedrockServerSession session, LoginData loginData) {
		this.server = server;
		this.session = session;
		this.loginData = loginData;

		this.setDevice(loginData.getDevice());
		this.setXuid(loginData.getXuid());
		this.setUsername(loginData.getUsername());
		this.setSkin(loginData.getSkin());
	}

	public void initialize() {
		this.server.getScheduler().prepareTask(() -> {
			try {
				this.data = this.server.getPlayerDataProvider().load(this.getUuid());

				if (this.data == null) {
					this.data = new PlayerData();
				}

				this.completePlayerInitialization();
			} catch (IOException exception) {
				log.error("Failed to load data of " + this.getUuid(), exception);
				this.disconnect();
			}
		}).async().schedule();
	}

	private void completePlayerInitialization() {
		this.server.getScheduler().prepareTask(() -> {
			if (this.server.getPlayers().size() >= this.server.getSettings().getMaximumPlayers()) {
				var packet = new PlayStatusPacket();
				packet.setStatus(PlayStatusPacket.Status.FAILED_SERVER_FULL_SUB_CLIENT);
				this.session.sendPacket(packet);
				return;
			}

			// Disconnect the player's other session if they're logged in on another device
			for (Player player : this.server.getPlayers()) {
				if (player.getUuid().equals(this.getUuid()) || player.getXuid().equals(this.getXuid())) {
					player.disconnect();
				}
			}

			this.server.addPlayer(this);

			this.setPitch(this.data.getPitch());
			this.setYaw(this.data.getYaw());
			this.setHeadYaw(this.data.getYaw());
			this.setPosition(this.data.getPosition());

			this.spawnTo(this);

			this.setGamemode(this.data.getGamemode());


			var startGamePacket = new StartGamePacket();
			startGamePacket.setUniqueEntityId(this.getId());
			startGamePacket.setRuntimeEntityId(this.getId());
			startGamePacket.setPlayerGameType(GameType.from(this.data.getGamemode().ordinal()));
			startGamePacket.setPlayerPosition(this.getPosition());
			startGamePacket.setRotation(Vector2f.from(this.getPitch(), this.getYaw()));
			startGamePacket.setSeed(-1L);
			startGamePacket.setDimensionId(0);
			startGamePacket.setTrustingPlayers(false);
			startGamePacket.setLevelGameType(GameType.SURVIVAL);
			startGamePacket.setDifficulty(1);
			startGamePacket.setDefaultSpawn(Vector3i.from(0, 60, 0));
			startGamePacket.setDayCycleStopTime(7000);
			startGamePacket.setLevelName(this.server.getSettings().getMotd());
			startGamePacket.setLevelId("");
			startGamePacket.setGeneratorId(1);
			startGamePacket.setDefaultPlayerPermission(PlayerPermission.MEMBER);
			startGamePacket.setServerChunkTickRange(8);
			startGamePacket.setVanillaVersion(Network.CODEC.getMinecraftVersion());
			startGamePacket.setPremiumWorldTemplateId("");
			startGamePacket.setInventoriesServerAuthoritative(true);
			startGamePacket.getGamerules().add(new GameRuleData<>("showcoordinates", true));
			// startGamePacket.setItemEntries(); // todo

			var movementSettings = new SyncedPlayerMovementSettings();
			movementSettings.setMovementMode(AuthoritativeMovementMode.CLIENT);
			movementSettings.setRewindHistorySize(0);
			movementSettings.setServerAuthoritativeBlockBreaking(false);

			startGamePacket.setPlayerMovementSettings(movementSettings);
			startGamePacket.setCommandsEnabled(true);
			startGamePacket.setMultiplayerGame(true);
			startGamePacket.setBroadcastingToLan(true);
			startGamePacket.setMultiplayerCorrelationId(UUID.randomUUID().toString());
			startGamePacket.setXblBroadcastMode(GamePublishSetting.PUBLIC);
			startGamePacket.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
			startGamePacket.setCurrentTick(this.server.getCurrentTick());
			startGamePacket.setServerEngine("");
			startGamePacket.setPlayerPropertyData(NbtMap.EMPTY);
			startGamePacket.setWorldTemplateId(new UUID(0, 0));
			startGamePacket.setWorldEditor(false);
			startGamePacket.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
			this.sendPacket(startGamePacket);

			var biomeDefinitionPacket = new BiomeDefinitionListPacket();
			biomeDefinitionPacket.setDefinitions(BedrockResourceLoader.BIOME_DEFINITIONS);
			this.sendPacket(biomeDefinitionPacket);

			var availableEntityIdentifiersPacket = new AvailableEntityIdentifiersPacket();
			availableEntityIdentifiersPacket.setIdentifiers(BedrockResourceLoader.ENTITY_IDENTIFIERS);
			this.sendPacket(availableEntityIdentifiersPacket);

			var creativeContentPacket = new CreativeContentPacket();
			// todo: creative items
			this.sendPacket(creativeContentPacket);

			var craftingDataPacket = new CraftingDataPacket();
			// todo: crafting data
			this.sendPacket(craftingDataPacket);


			// Sent the full player list to this player
			Set<PlayerListPacket.Entry> entries = new HashSet<>();
			for (Player player : this.server.getPlayers()) {
				entries.add(player.getPlayerListEntry());
			}

			this.playerList.addEntries(entries);

			PlayStatusPacket playStatusPacket = new PlayStatusPacket();
			playStatusPacket.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
			this.sendPacket(playStatusPacket);

			log.info("{} logged in [X = {}, Y = {}, Z = {}]", this.getUsername(), this.getPosition().getX(), this.getPosition().getY(), this.getPosition().getZ());

			this.setSpawned(true);
		}).schedule();
	}

	public void sendPacket(BedrockPacket packet) {
		if (!this.session.isClosed()) {
			this.session.sendPacket(packet);
		}
	}

	public void setGamemode(Gamemode gamemode) {
		var currentGamemode = this.data.getGamemode();
		if (gamemode != currentGamemode) {

			this.data.setGamemode(gamemode);

			var packet = new SetPlayerGameTypePacket();
			packet.setGamemode(gamemode.ordinal());
			this.sendPacket(packet);
		}
	}

	public void onDisconnect() {
		if (this.isSpawned()) {
			this.server.getScheduler().prepareTask(() -> {
				try {
					this.server.getPlayerDataProvider().save(this.getUuid(), this.data);
				} catch (IOException exception) {
					log.error("Failed to save data of " + this.getUuid(), exception);
				}

				this.getServer().getScheduler().prepareTask(this::despawnFromAll).schedule();
			}).async().schedule();

			// remove the player from the player list of others
			for (Player player : this.getServer().getPlayers()) {
				player.getPlayerList().removeEntry(this.getPlayerListEntry());
			}
		}

		log.info("{} player left the server", this.getUsername());
	}

	public void disconnect() {
		this.session.disconnect();
	}

	public void disconnect(String reason) {
		this.session.disconnect(reason);
	}
}