package com.example.legacyscoreboard;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LEGACY SMP sidebar. Logo and icons are bitmap glyphs (private-use characters) that this
 * mod's own assets add to the default font, so the jar alone is enough in single player.
 */
public class LegacyScoreboard implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("legacyscoreboard");

    // ---- TWEAK THESE ----
    private static final String CREATOR_NAME = "MoiNinja_xD";   // gets the CREATOR badge
    private static final int TOKENS = 0;
    private static final int MONEY = 100;                        // stays fixed
    private static final int SHARDS = 0;
    private static final int VOTES = 44;
    private static final int VOTES_NEEDED = 75;
    private static final String FOOTER = "PLAY.ASCENDIAMC.COM";

    // colours sampled from the screenshot
    private static final int LABEL = 0xFFFFEC;
    private static final int C_TOKENS = 0x8E70D8;
    private static final int C_MONEY = 0xF2C94C;
    private static final int C_SHARDS = 0xE01AD8;
    private static final int C_RED = 0xD8402A;
    private static final int C_GREEN = 0x62E062;
    private static final int C_TIME = 0x8CF0FF;
    private static final int C_NAME = 0xC066D8;
    private static final int C_FOOTER = 0xFDFDA1;

    // glyphs defined in assets/minecraft/font/default.json
    private static final String G_LOGO = "\uE000";
    private static final String G_BADGE = "\uE001";
    private static final String G_TOKENS = "\uE002";
    private static final String G_MONEY = "\uE003";
    private static final String G_SHARDS = "\uE004";
    private static final String G_KILLS = "\uE005";
    private static final String G_DEATHS = "\uE006";
    private static final String G_VOTES = "\uE007";
    private static final String G_CLOCK = "\uE008";

    private static final String OBJECTIVE = "legacyscoreboard";
    private static final int LINE_COUNT = 11;
    private static final int KILLS_LINE = 5;
    private static final int DEATHS_LINE = 6;
    private static final int TIME_LINE = 8;

    private static final Map<String, int[]> STATS = new HashMap<>(); // uuid -> {kills, deaths, playSeconds}
    private static long tickCounter = 0;

    // extra editable slots appended to the stats array (indices 0-2 are kills, deaths, playSeconds)
    private static final int I_TOKENS = 3, I_MONEY = 4, I_SHARDS = 5, I_VOTES = 6, STAT_SLOTS = 7;
    // /board edit <player> <stat> <value>  -> stat name -> array index
    private static final Map<String, Integer> EDITABLE = new java.util.LinkedHashMap<>();
    static {
        EDITABLE.put("kills", 0);
        EDITABLE.put("deaths", 1);
        EDITABLE.put("playtime", 2); // value is in MINUTES
        EDITABLE.put("tokens", I_TOKENS);
        EDITABLE.put("money", I_MONEY);
        EDITABLE.put("shards", I_SHARDS);
        EDITABLE.put("votes", I_VOTES);
    }

    @Override
    public void onInitialize() {
        load();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> show(handler.player));

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity victim)) return;
            stats(victim)[1]++;
            if (source.getAttacker() instanceof ServerPlayerEntity killer && killer != victim) {
                stats(killer)[0]++;
                refreshKillsDeaths(killer);
            }
            refreshKillsDeaths(victim);
            save();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 20 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                stats(p)[2]++;
            }
            if (tickCounter % 1200 == 0) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    p.networkHandler.sendPacket(scorePacket(p, TIME_LINE));
                }
                save();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("board")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .then(CommandManager.literal("edit")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("stat", StringArgumentType.word())
                                                .suggests((c, b) -> CommandSource.suggestMatching(EDITABLE.keySet(), b))
                                                .then(CommandManager.argument("value", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> {
                                                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                                            String stat = StringArgumentType.getString(ctx, "stat").toLowerCase();
                                                            int value = IntegerArgumentType.getInteger(ctx, "value");
                                                            Integer idx = EDITABLE.get(stat);
                                                            if (idx == null) {
                                                                ctx.getSource().sendError(Text.literal("Unknown stat '" + stat
                                                                        + "'. Use: " + String.join(", ", EDITABLE.keySet())));
                                                                return 0;
                                                            }
                                                            stats(target)[idx] = idx == 2 ? value * 60 : value;
                                                            save();
                                                            sendAllLines(target);
                                                            ctx.getSource().sendFeedback(() -> Text.literal("Set " + stat + " of "
                                                                    + target.getName().getString() + " to " + value), true);
                                                            return 1;
                                                        })))))));
        LOGGER.info("Legacy Scoreboard loaded");
    }

    // ---------- stats ----------
    private static int[] stats(ServerPlayerEntity p) {
        String id = p.getUuid().toString();
        int[] s = STATS.computeIfAbsent(id, k -> new int[]{0, 0, 0, TOKENS, MONEY, SHARDS, VOTES});
        if (s.length < STAT_SLOTS) {
            int old = s.length;
            s = Arrays.copyOf(s, STAT_SLOTS);
            if (old <= I_TOKENS) s[I_TOKENS] = TOKENS;
            if (old <= I_MONEY) s[I_MONEY] = MONEY;
            if (old <= I_SHARDS) s[I_SHARDS] = SHARDS;
            if (old <= I_VOTES) s[I_VOTES] = VOTES;
            STATS.put(id, s);
        }
        return s;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("legacyscoreboard_stats.json");
    }

    private static void load() {
        try {
            if (Files.exists(file())) {
                Map<String, int[]> m = new Gson().fromJson(Files.readString(file()),
                        new TypeToken<Map<String, int[]>>() {}.getType());
                if (m != null) STATS.putAll(m);
            }
        } catch (Exception e) {
            LOGGER.error("Could not read stats", e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), new Gson().toJson(STATS));
        } catch (Exception e) {
            LOGGER.error("Could not save stats", e);
        }
    }

    // ---------- lines ----------
    private static Text colored(String s, int rgb) {
        return Text.literal(s).styled(st -> st.withColor(rgb));
    }

    /** glyph (own colours) + label + value */
    private static Text row(String glyph, String label, int valueColor, String value) {
        return Text.empty()
                .append(Text.literal(glyph + " "))
                .append(colored(label + " ", LABEL))
                .append(colored(value, valueColor));
    }

    private static String playtime(int seconds) {
        int minutes = seconds / 60;
        int h = minutes / 60;
        int m = minutes % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private static Text line(ServerPlayerEntity p, int index) {
        int[] s = stats(p);
        String name = p.getName().getString();
        boolean creator = name.equals(CREATOR_NAME);
        return switch (index) {
            case 0 -> Text.empty()
                    .append(creator ? Text.literal(G_BADGE + " ") : colored("MEMBER ", 0xAAAAAA))
                    .append(colored(name, creator ? C_NAME : 0xFFFFFF));
            case 1 -> row(G_TOKENS, "AC-Tokens:", C_TOKENS, String.valueOf(s[I_TOKENS]));
            case 2 -> row(G_MONEY, "Money:", C_MONEY, String.valueOf(s[I_MONEY]));
            case 3 -> row(G_SHARDS, "Shards:", C_SHARDS, "\u2726" + s[I_SHARDS]);
            case 5 -> row(G_KILLS, "Kills:", C_RED, String.valueOf(s[0]));
            case 6 -> row(G_DEATHS, "Deaths:", C_RED, String.valueOf(s[1]));
            case 7 -> row(G_VOTES, "VoteParty:", C_GREEN, s[I_VOTES] + " / " + VOTES_NEEDED);
            case 8 -> row(G_CLOCK, "Playtime:", C_TIME, playtime(s[2]));
            case 10 -> colored(FOOTER, C_FOOTER);
            default -> Text.literal(" "); // blank spacer lines (4 and 9)
        };
    }

    private static ScoreboardScoreUpdateS2CPacket scorePacket(ServerPlayerEntity p, int index) {
        return new ScoreboardScoreUpdateS2CPacket("line" + index, OBJECTIVE, LINE_COUNT - index,
                Optional.of(line(p, index)), Optional.<NumberFormat>of(BlankNumberFormat.INSTANCE));
    }

    // ---------- sending ----------
    private static void show(ServerPlayerEntity p) {
        ScoreboardObjective obj = new ScoreboardObjective(new Scoreboard(), OBJECTIVE, ScoreboardCriterion.DUMMY,
                Text.literal(G_LOGO), ScoreboardCriterion.RenderType.INTEGER, false, BlankNumberFormat.INSTANCE);
        p.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(obj, 0));
        p.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, obj));
        for (int i = 0; i < LINE_COUNT; i++) {
            p.networkHandler.sendPacket(scorePacket(p, i));
        }
    }

    private static void sendAllLines(ServerPlayerEntity p) {
        for (int i = 0; i < LINE_COUNT; i++) {
            p.networkHandler.sendPacket(scorePacket(p, i));
        }
    }

    private static void refreshKillsDeaths(ServerPlayerEntity p) {
        p.networkHandler.sendPacket(scorePacket(p, KILLS_LINE));
        p.networkHandler.sendPacket(scorePacket(p, DEATHS_LINE));
    }
}
