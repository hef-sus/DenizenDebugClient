ErrorConfig:
  type: data
  ErrorPlayers:
  - YourNameHere
  - EnergoBro
  - isnsest_
  - hef_sus



#Reqs: denizenM, denizen reflect, PacketEvents



ErrorHandle:
  type: world
  debug: false
  definitions: script|line|error|timestamp
  events:
    on script generates error priority:-100:
    - determine cancelled passively
    - announce "<&color[#ff0000]> ⚠ Error ⚠<n><&f>⏺ Script: <context.script.name||null><n><&f>⏺ Line: <context.line||null><n><&f>⏺ Message: <context.message||null>" to_console
    - run errorhandle.send def.script:<context.script.name||null> def.line:<context.line||null> def.error:<context.message||null> async
    - run errorhandle.save def.script:<context.script.name||null> def.line:<context.line||null> def.error:<context.message||null> async
    after player joins:
    - inject ErrorHandle.seencheck
  seencheck:
    - define allowedPlayers <script[ErrorConfig].data_key[ErrorPlayers]>
    - if !<[allowedPlayers].contains[<player.name>]>:
      - stop
    - define channel dzdebug:unseen
    - define buf <invoke[io.netty.buffer.Unpooled.buffer()]>
    - define fb <invoke[new net.minecraft.network.FriendlyByteBuf(buf)]>
    - define errorlist <server.flag[ErrorHandle.errors].filter[get[unseen].contains[<player.name>]]||<list>>
    - define esize <[errorlist].size||0>
    - invoke fb.writeBoolean(true)
    - invoke fb.writeVarInt(<[esize]>)
    - foreach <[errorlist]> as:e:
      - define s <[e.script]>
      - define l <[e.line]>
      - define er <[e.error]>
      - define t <[e.time]>
      - invoke 'fb.writeUtf(s, 1000)'
      - invoke 'fb.writeUtf(l, 60)'
      - invoke 'fb.writeUtf(er, 32000)'
      - invoke fb.writeVarInt(1)
      - invoke fb.writeLong(t)
    - define out <invoke[io.netty.buffer.ByteBufUtil.getBytes(buf)]>
    - define packet '<invoke[new WrapperPlayServerPluginMessage("dzdebug:unseen",out)]>'
    - invoke buf.release()
    - invoke 'PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet)'
  send:
    - define channel dzdebug:error
    - define allowedPlayers <script[ErrorConfig].data_key[ErrorPlayers]>
    - define buf <invoke[io.netty.buffer.Unpooled.buffer()]>
    - define fb <invoke[new net.minecraft.network.FriendlyByteBuf(buf)]>
    - invoke 'fb.writeUtf(script, 1000)'
    - invoke 'fb.writeUtf(line, 60)'
    - invoke 'fb.writeUtf(error, 32000)'
    - invoke fb.writeVarInt(1)
    - define out <invoke[io.netty.buffer.ByteBufUtil.getBytes(buf)]>
    - define packet '<invoke[new WrapperPlayServerPluginMessage("dzdebug:error",out)]>'
    - invoke buf.release()
    - foreach <server.online_players.filter_tag[<[allowedPlayers].contains[<[filter_value].name>]>]> as:p:
      - if !<[p].plugin_channels.contains[<[channel]>]>:
        - foreach next
      - invoke 'PacketEvents.getAPI().getPlayerManager().sendPacket(p, packet)'

  save:
  - define allowedPlayers <script[ErrorConfig].data_key[ErrorPlayers]>
  - define seenPlayers <[allowedPlayers].shared_contents[<server.online_players.parse[name]>]>
  - define unseenPlayers <[allowedPlayers].exclude[<[seenPlayers]>]>
  - define ErrData <map>
  - define ErrData <[ErrData].with[script].as[<[script]>]>
  - define ErrData <[ErrData].with[line].as[<[line]>]>
  - define ErrData <[ErrData].with[error].as[<[error]>]>
  - define ErrData <[ErrData].with[time].as[<util.time_now.epoch_millis>]>
  - define ErrData <[ErrData].with[unseen].as[<[unseenPlayers]>]>
  - flag server ErrorHandle.errors:->:<[errData]> expire:40h




# dont touch


cleardenizenlogs:
  type: command
  debug: false
  description: cleardenizenlogs
  name: cleardenizenlogs
  usage: /cleardenizenlogs
  permission: admin.logs
  script:
  - flag server ErrorHandle.errors:!
  - narrate "<green>Cleared unseen logs server-side"


serverlastestlog:
  type: command
  debug: false
  description: serverlastestlog
  name: serverlastestlog
  usage: /serverlastestlog
  permission: admin.logs
  script:
    - define path ../../logs/latest.log
    - if !<util.has_file[<[path]>]>:
        - debug error "Couldn't find the log: <[path]>"
        - stop
    - ~fileread path:<[path]> save:log
    - define content <entry[log].data.utf8_decode.if_null[null]>
    - if <[content]> == null || <[content].length> == 0:
        - debug error "Failed to read log: <[path]><n>Is file reading enabled in Denizen's config.yml?"
        - stop
    - ~webget https://api.mclo.gs/1/log method:POST headers:<map[Content-Type=application/x-www-form-urlencoded]> data:content=<[content].url_encode> timeout:30s save:res
    - if <entry[res].failed>:
        - debug error "Log upload failed!"
        - stop
    - define json <entry[res].result.parse_yaml.if_null[null]>
    - if <[json]> == null || !<[json].get[success].if_null[false]>:
        - debug error "mclo.gs rejected the upload: <[json].get[error].if_null[unknown error]>"
        - stop
    - define url <[json].get[url]>
    - narrate "<green>Log uploaded: <[url].on_hover[Click to open].on_click[<[url]>].type[open_url]>"


import:
    com.github.retrooper.packetevents.PacketEvents
    com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
    com.github.retrooper.packetevents.wrapper.PacketWrapper
    com.github.retrooper.packetevents.netty.buffer.ByteBufHelper
    net.kyori.adventure.text.Component
    net.kyori.adventure.text.format.NamedTextColor
    org.bukkit.Bukkit
    org.bukkit.entity.Player
    java.io.ByteArrayOutputStream
    java.nio.charset.StandardCharsets
    java.lang.reflect.Array.*
    net.minecraft.network.*