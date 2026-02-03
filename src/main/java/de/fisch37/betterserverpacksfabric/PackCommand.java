package de.fisch37.betterserverpacksfabric;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.TextArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;

import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.server.command.CommandManager.*;

public class PackCommand {
    public final static Text MSG_PREFIX = Text.literal("")
            .append(Text.literal("[").formatted(Formatting.YELLOW))
            .append(Text.literal("BSP").formatted(Formatting.AQUA))
            .append(Text.literal("] ").formatted(Formatting.YELLOW));
    private static final SimpleCommandExceptionType INVALID_URI_EXCEPTION = new SimpleCommandExceptionType(
            Text.translatableWithFallback("bsp.commands.exc.invalid_uri", "The pack URI is malformed. You can try fixing this in the config or just use /pack set <url> again")
    );

    private static Collection<ServerPlayerEntity> allPlayers(CommandContext<ServerCommandSource> context) {
        return context.getSource().getServer().getPlayerManager().getPlayerList();
    }

    private static LiteralArgumentBuilder<ServerCommandSource> makeCommand(CommandRegistryAccess registryAccess) {
        return literal("pack")
                .requires(CommandManager.requirePermissionLevel(ADMINS_CHECK))
                .then(literal("set")
                        .then(argument("url", string())
                                .executes(PackCommand::setPack)
                                .then(literal("push")
                                        .executes(context -> PackCommand.setPack(context, allPlayers(context)))
                                        .then(argument("players", EntityArgumentType.players())
                                                .executes(context -> PackCommand.setPack(
                                                        context,
                                                        EntityArgumentType.getPlayers(context, "players")
                                                ))
                                        )
                                )
                        )
                )
                .then(literal("remove")
                        .executes(PackCommand::disablePack)
                )
                .then(literal("reload")
                        .executes(PackCommand::reloadPack)
                        .then(literal("push")
                                .executes(context -> PackCommand.reloadPack(context, allPlayers(context)))
                                .then(argument("players", EntityArgumentType.players())
                                        .executes(context -> PackCommand.reloadPack(
                                                context,
                                                EntityArgumentType.getPlayers(context, "players")
                                        ))
                                )
                        )
                )
                .then(literal("push")
                        .executes(context -> ResourcePackHandler.pushTo(allPlayers(context)))
                        .then(argument("players", EntityArgumentType.players())
                                .executes(context -> ResourcePackHandler.pushTo(
                                        EntityArgumentType.getPlayers(context, "players")
                                ))
                        )
                )
                .then(literal("required")
                        .executes(PackCommand::getRequired)
                        .then(argument("required", BoolArgumentType.bool())
                                .executes(PackCommand::setRequired)
                        )
                ).then(literal("prompt")
                        .executes(PackCommand::showPrompt)
                        .then(argument("prompt", TextArgumentType.text(registryAccess))
                                .executes(PackCommand::setPrompt)
                        )
                        .then(literal("clear")
                                .executes(PackCommand::clearPrompt)
                        )
                ).then(literal("info")
                        .executes(PackCommand::showInfo));
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {
                    dispatcher.register(makeCommand(registryAccess));
                }
        );
    }

    private static void updateHashWithContext(ServerCommandSource source) {
        updateHashWithContext(source, Collections.emptyList());
    }

    private static void updateHashWithContext(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        source.sendFeedback(() -> MSG_PREFIX.copy()
                .append("Updating pack hash...")
                ,
                true
        );
        Main.updateHash().whenComplete((result, exc) -> {
            // Let's all hope that this doesn't cause threading issues :+1:
            if (exc != null) {
                Main.LOGGER.error("Failed to update hash", exc);
                source.sendFeedback(() -> MSG_PREFIX.copy()
                                .append(Text.literal(
                                        "Failed to update hash."
                                                + "Please check the server logs for more information."
                                        )
                                        .formatted(Formatting.RED)
                                )
                        ,
                        true
                );
            } else if (result) {
                // Hash updated
                source.sendFeedback(() -> MSG_PREFIX.copy()
                                .append("Pack Hash has been updated!")
                        ,
                        true);

                if (!players.isEmpty()) {
                    source.sendFeedback(() -> MSG_PREFIX.copy()
                                    .append("Pushing to players...")
                            ,
                            true);
                    ResourcePackHandler.pushTo(players);
                }
            } else {
                // Hash removed (no pack selected)
                source.sendFeedback(() -> MSG_PREFIX.copy()
                                .append("BetterServerPacks has been disabled. ")
                                .append("This cannot be pushed to the players :(")
                        ,
                        true
                );
            }
        });
    }

    private static int setPack(CommandContext<ServerCommandSource> context) {
        return setPack(context, Collections.emptyList());
    }

    private static int setPack(CommandContext<ServerCommandSource> context, Collection<ServerPlayerEntity> players) {
        final Supplier<Text> INVALID_URL_ERROR = (
                () -> MSG_PREFIX.copy()
                .append(Text.literal("The text supplied is not a valid URL")
                        .formatted(Formatting.RED))
        );

        ServerCommandSource source = context.getSource();
        String url = StringArgumentType.getString(context, "url");
        URL parsedUrl;

        try { parsedUrl = new URI(url).toURL(); }
        catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            context.getSource().sendFeedback(INVALID_URL_ERROR, false);
            return 0;
        }
        String protocol = parsedUrl.getProtocol();
        if ((!protocol.equals("https")) && (!protocol.equals("http"))) {
            source.sendFeedback(INVALID_URL_ERROR, false);
            return 0;
        }

        Main.config.url.set(url).save();
        source.sendFeedback( () -> MSG_PREFIX.copy()
                .append("Pack URL has been updated. Reloading hash...")
                ,
                true
        );
        updateHashWithContext(source, players);
        return 1;
    }

    private static int disablePack(CommandContext<ServerCommandSource> context) {
        Main.config.url.set("").save();
        updateHashWithContext(context.getSource());
        return 1;
    }

    private static int reloadPack(CommandContext<ServerCommandSource> context) {
        return reloadPack(context, Collections.emptyList());
    }

    private static int reloadPack(CommandContext<ServerCommandSource> context, Collection<ServerPlayerEntity> players) {
        updateHashWithContext(context.getSource(), players);
        return 1;
    }

    private static int getRequired(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        Boolean required = Main.config.required.get();

        source.sendFeedback(() -> MSG_PREFIX.copy()
                .append("Pack is " + (required ? "required" : "optional"))
                ,
                false
        );
        return 1;
    }

    private static int setRequired(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        Boolean required = BoolArgumentType.getBool(context, "required");
        Main.config.required.set(required).save();

        source.sendFeedback( () -> MSG_PREFIX.copy()
                .append("Pack is now " + (required ? "required" : "optional"))
                ,
                true
        );
        return 1;
    }

    private static int showPrompt(CommandContext<ServerCommandSource> context) {
        final Optional<Text> prompt = Main.config.getPrompt(context.getSource().getRegistryManager());
        context.getSource().sendFeedback(
                () -> prompt.map(
                        text -> MSG_PREFIX.copy()
                                .append("Current Prompt is: ")
                                .append(text)
                ).orElseGet(
                        () -> MSG_PREFIX.copy()
                                .append("No prompt is set")
                ),
                false
        );
        return 1;
    }

    private static int setPrompt(CommandContext<ServerCommandSource> context) {
        final Text prompt = TextArgumentType.getTextArgument(context, "prompt");
        Main.config.setPrompt(prompt, context.getSource().getRegistryManager())
                .save();
        context.getSource().sendFeedback(
                () -> MSG_PREFIX.copy()
                        .append("Prompt has been set to: ")
                        .append(prompt)
                ,
                true
        );
        return 1;
    }

    private static int clearPrompt(CommandContext<ServerCommandSource> context) {
        Main.config.setPrompt(null, null);
        context.getSource().sendFeedback(
                () -> MSG_PREFIX.copy()
                        .append("Prompt has been removed")
                ,
                true
        );
        return 1;
    }

    private static int showInfo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String url = Main.config.url.get();
        URI uriObj;
        try {
            uriObj = new URI(url);
        } catch (URISyntaxException e) {
            throw INVALID_URI_EXCEPTION.create();
        }
        boolean required = Main.config.required.get();
        Optional<Text> prompt = Main.config.getPrompt(context.getSource().getRegistryManager());
        if (!url.isEmpty()) {
            context.getSource().sendFeedback(() ->
                            MSG_PREFIX.copy()
                                    .append("Pack URL: ")
                                    .append(Text.literal(url)
                                            .fillStyle(Style.EMPTY.withClickEvent(new ClickEvent.OpenUrl(uriObj)))
                                            .formatted(Formatting.GREEN)
                                            .formatted(Formatting.UNDERLINE)
                                    )
                                    .append("\n")
                                    .append("Pack hash: ")
                                    .append(Optional.ofNullable(Main.getHashString())
                                            .map(s -> Text.literal(s)
                                                    .formatted(Formatting.LIGHT_PURPLE)
                                                    .formatted(Formatting.ITALIC)
                                            )
                                            .orElse(Text.literal("undetermined").formatted(Formatting.GRAY))
                                    )
                                    .append("\n")
                                    .append("Pack is ")
                                    .append(
                                            required
                                                    ? Text.literal("required")
                                                    .formatted(Formatting.RED)
                                                    : Text.literal("optional")
                                                    .formatted(Formatting.YELLOW)
                                    )
                                    .append("\n")
                                    .append(prompt
                                            .map(text -> Text.literal("Prompt: \n    ")
                                                    .append(text)
                                            )
                                            .orElseGet(() -> Text.literal("No prompt is set"))
                                    )
                    ,
                    false
            );
        } else {
            context.getSource().sendFeedback(() ->
                    MSG_PREFIX.copy()
                            .append("No resourcepack is set")
                    ,
                    false
            );
        }

        return 1;
    }
}
