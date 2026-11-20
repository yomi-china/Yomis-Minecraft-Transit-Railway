package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class Route extends NameColorDataBase implements IGui {

	public RouteType routeType;
	public boolean isLightRailRoute;
	public boolean isHidden;
	public boolean disableNextStationAnnouncements;
	public CircularState circularState;
	public String lightRailRouteNumber;
	public final List<RoutePlatform> platformIds;

	private static final String KEY_PLATFORM_IDS = "platform_ids";
	private static final String KEY_CUSTOM_DESTINATIONS = "custom_destinations";
	private static final String KEY_STOP_WITHOUT_OPENING_DOORS = "stop_without_opening_doors";
	private static final String KEY_CUSTOM_DWELL_TIMES = "custom_dwell_times";
	private static final String KEY_PLATFORM_DWELL_TIMES = "platform_dwell_times";
	private static final String KEY_CUSTOM_ADC_TIMES = "custom_adc_times";
	private static final String KEY_PLATFORM_ADC_TIMES = "platform_adc_times";
	private static final String KEY_ROUTE_TYPE = "route_type";
	private static final String KEY_IS_LIGHT_RAIL_ROUTE = "is_light_rail_route";
	private static final String KEY_LIGHT_RAIL_ROUTE_NUMBER = "light_rail_route_number";
	private static final String KEY_IS_ROUTE_HIDDEN = "is_route_hidden";
	private static final String KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS = "disable_next_station_announcements";
	private static final String KEY_CIRCULAR_STATE = "circular_state";

	public Route(TransportMode transportMode) {
		this(0, transportMode);
	}

	public Route(long id, TransportMode transportMode) {
		super(id, transportMode);
		platformIds = new ArrayList<>();
		routeType = RouteType.NORMAL;
		isLightRailRoute = false;
		circularState = CircularState.NONE;
		lightRailRouteNumber = "";
		isHidden = false;
		disableNextStationAnnouncements = false;
	}

	public Route(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);

		platformIds = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_PLATFORM_IDS, platformId -> platformIds.add(new RoutePlatform(platformId.asIntegerValue().asLong())));

		final List<String> customDestinations = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_CUSTOM_DESTINATIONS, customDestination -> customDestinations.add(customDestination.asStringValue().asString()));

		final List<Boolean> stopWithoutOpeningDoorsList = new ArrayList<>();
		if (map.containsKey(KEY_STOP_WITHOUT_OPENING_DOORS)) {
			messagePackHelper.iterateArrayValue(KEY_STOP_WITHOUT_OPENING_DOORS, value -> stopWithoutOpeningDoorsList.add(value.asBooleanValue().getBoolean()));
		}

		final List<Boolean> customDwellTimesList = new ArrayList<>();
		if (map.containsKey(KEY_CUSTOM_DWELL_TIMES)) {
			messagePackHelper.iterateArrayValue(KEY_CUSTOM_DWELL_TIMES, value -> customDwellTimesList.add(value.asBooleanValue().getBoolean()));
		}

		final List<Integer> platformDwellTimesList = new ArrayList<>();
		if (map.containsKey(KEY_PLATFORM_DWELL_TIMES)) {
			messagePackHelper.iterateArrayValue(KEY_PLATFORM_DWELL_TIMES, value -> platformDwellTimesList.add(value.asIntegerValue().asInt()));
		}

		for (int i = 0; i < Math.min(platformIds.size(), customDestinations.size()); i++) {
			platformIds.get(i).customDestination = customDestinations.get(i);
		}
		for (int i = 0; i < Math.min(platformIds.size(), stopWithoutOpeningDoorsList.size()); i++) {
			platformIds.get(i).stopWithoutOpeningDoors = stopWithoutOpeningDoorsList.get(i);
		}
		for (int i = 0; i < Math.min(platformIds.size(), customDwellTimesList.size()); i++) {
			platformIds.get(i).customDwellTime = customDwellTimesList.get(i);
		}
		for (int i = 0; i < Math.min(platformIds.size(), platformDwellTimesList.size()); i++) {
			platformIds.get(i).dwellTime = platformDwellTimesList.get(i);
		}

		final List<Boolean> customAdcTimesList = new ArrayList<>();
		if (map.containsKey(KEY_CUSTOM_ADC_TIMES)) {
			messagePackHelper.iterateArrayValue(KEY_CUSTOM_ADC_TIMES, value -> customAdcTimesList.add(value.asBooleanValue().getBoolean()));
		}

		final List<Integer> platformAdcTimesList = new ArrayList<>();
		if (map.containsKey(KEY_PLATFORM_ADC_TIMES)) {
			messagePackHelper.iterateArrayValue(KEY_PLATFORM_ADC_TIMES, value -> platformAdcTimesList.add(value.asIntegerValue().asInt()));
		}

		for (int i = 0; i < Math.min(platformIds.size(), customAdcTimesList.size()); i++) {
			platformIds.get(i).customAdcTime = customAdcTimesList.get(i);
		}
		for (int i = 0; i < Math.min(platformIds.size(), platformAdcTimesList.size()); i++) {
			platformIds.get(i).adcTime = platformAdcTimesList.get(i);
		}

		routeType = EnumHelper.valueOf(RouteType.NORMAL, messagePackHelper.getString(KEY_ROUTE_TYPE));
		isLightRailRoute = messagePackHelper.getBoolean(KEY_IS_LIGHT_RAIL_ROUTE);
		isHidden = messagePackHelper.getBoolean(KEY_IS_ROUTE_HIDDEN);
		disableNextStationAnnouncements = messagePackHelper.getBoolean(KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS);
		lightRailRouteNumber = messagePackHelper.getString(KEY_LIGHT_RAIL_ROUTE_NUMBER);
		circularState = EnumHelper.valueOf(CircularState.NONE, messagePackHelper.getString(KEY_CIRCULAR_STATE));
	}

	@Deprecated
	public Route(CompoundTag compoundTag) {
		super(compoundTag);

		platformIds = new ArrayList<>();
		final long[] platformIdsArray = compoundTag.getLongArray(KEY_PLATFORM_IDS);
		for (final long platformId : platformIdsArray) {
			platformIds.add(new RoutePlatform(platformId));
		}

		routeType = EnumHelper.valueOf(RouteType.NORMAL, compoundTag.getString(KEY_ROUTE_TYPE));
		isLightRailRoute = compoundTag.getBoolean(KEY_IS_LIGHT_RAIL_ROUTE);
		isHidden = compoundTag.getBoolean(KEY_IS_ROUTE_HIDDEN);
		disableNextStationAnnouncements = compoundTag.getBoolean(KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS);
		lightRailRouteNumber = compoundTag.getString(KEY_LIGHT_RAIL_ROUTE_NUMBER);
		circularState = EnumHelper.valueOf(CircularState.NONE, compoundTag.getString(KEY_CIRCULAR_STATE));
	}

	public Route(FriendlyByteBuf packet) {
		super(packet);

		platformIds = new ArrayList<>();
		final int platformCount = packet.readInt();
		for (int i = 0; i < platformCount; i++) {
			final RoutePlatform routePlatform = new RoutePlatform(packet.readLong());
			routePlatform.customDestination = packet.readUtf(PACKET_STRING_READ_LENGTH);
			routePlatform.stopWithoutOpeningDoors = packet.readBoolean();
			routePlatform.customDwellTime = packet.readBoolean();
			routePlatform.dwellTime = packet.readInt();
			routePlatform.customAdcTime = packet.readBoolean();
			routePlatform.adcTime = packet.readInt();
			platformIds.add(routePlatform);
		}

		routeType = EnumHelper.valueOf(RouteType.NORMAL, packet.readUtf(PACKET_STRING_READ_LENGTH));
		isLightRailRoute = packet.readBoolean();
		isHidden = packet.readBoolean();
		disableNextStationAnnouncements = packet.readBoolean();
		lightRailRouteNumber = packet.readUtf(PACKET_STRING_READ_LENGTH);
		circularState = EnumHelper.valueOf(CircularState.NONE, packet.readUtf(PACKET_STRING_READ_LENGTH));
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_PLATFORM_IDS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packLong(routePlatform.platformId);
		}

		messagePacker.packString(KEY_CUSTOM_DESTINATIONS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packString(routePlatform.customDestination);
		}

		messagePacker.packString(KEY_STOP_WITHOUT_OPENING_DOORS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packBoolean(routePlatform.stopWithoutOpeningDoors);
		}

		messagePacker.packString(KEY_CUSTOM_DWELL_TIMES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packBoolean(routePlatform.customDwellTime);
		}

		messagePacker.packString(KEY_PLATFORM_DWELL_TIMES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packInt(routePlatform.dwellTime);
		}

		messagePacker.packString(KEY_CUSTOM_ADC_TIMES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packBoolean(routePlatform.customAdcTime);
		}

		messagePacker.packString(KEY_PLATFORM_ADC_TIMES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packInt(routePlatform.adcTime);
		}

		messagePacker.packString(KEY_ROUTE_TYPE).packString(routeType.toString());
		messagePacker.packString(KEY_IS_LIGHT_RAIL_ROUTE).packBoolean(isLightRailRoute);
		messagePacker.packString(KEY_IS_ROUTE_HIDDEN).packBoolean(isHidden);
		messagePacker.packString(KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS).packBoolean(disableNextStationAnnouncements);
		messagePacker.packString(KEY_LIGHT_RAIL_ROUTE_NUMBER).packString(lightRailRouteNumber);
		messagePacker.packString(KEY_CIRCULAR_STATE).packString(circularState.toString());
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 13;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);
		packet.writeInt(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			lambda$writePacket$2(packet, routePlatform);
		}

		packet.writeUtf(routeType.toString());
		packet.writeBoolean(isLightRailRoute);
		packet.writeBoolean(isHidden);
		packet.writeBoolean(disableNextStationAnnouncements);
		packet.writeUtf(lightRailRouteNumber);
		packet.writeUtf(circularState.toString());
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_PLATFORM_IDS:
				platformIds.clear();
				final int platformCount = packet.readInt();
				for (int i = 0; i < platformCount; i++) {
					final RoutePlatform routePlatform = new RoutePlatform(packet.readLong());
					routePlatform.customDestination = packet.readUtf(PACKET_STRING_READ_LENGTH);
					routePlatform.stopWithoutOpeningDoors = packet.readBoolean();
					routePlatform.customDwellTime = packet.readBoolean();
					routePlatform.dwellTime = packet.readInt();
					routePlatform.customAdcTime = packet.readBoolean();
					routePlatform.adcTime = packet.readInt();
					platformIds.add(routePlatform);
				}
				break;
			case KEY_IS_LIGHT_RAIL_ROUTE:
				name = packet.readUtf(PACKET_STRING_READ_LENGTH);
				color = packet.readInt();
				routeType = EnumHelper.valueOf(RouteType.NORMAL, packet.readUtf(PACKET_STRING_READ_LENGTH));
				isLightRailRoute = packet.readBoolean();
				lightRailRouteNumber = packet.readUtf(PACKET_STRING_READ_LENGTH);
				isHidden = packet.readBoolean();
				disableNextStationAnnouncements = packet.readBoolean();
				circularState = EnumHelper.valueOf(CircularState.NONE, packet.readUtf(PACKET_STRING_READ_LENGTH));
				break;
			default:
				super.update(key, packet);
				break;
		}
	}

	@Override
	protected boolean hasTransportMode() {
		return true;
	}

	public void setPlatformIds(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_PLATFORM_IDS);
		packet.writeInt(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			lambda$setPlatformIds$3(packet, routePlatform);
		}
		sendPacket.accept(packet);
	}

	@Deprecated
	public void setPlatformIds(Consumer<FriendlyByteBuf> sendPacket, boolean legacy) {
		setPlatformIds(sendPacket);
	}

	public void setExtraData(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_IS_LIGHT_RAIL_ROUTE);
		packet.writeUtf(name);
		packet.writeInt(color);
		packet.writeUtf(routeType.toString());
		packet.writeBoolean(isLightRailRoute);
		packet.writeUtf(lightRailRouteNumber);
		packet.writeBoolean(isHidden);
		packet.writeBoolean(disableNextStationAnnouncements);
		packet.writeUtf(circularState.toString());
		sendPacket.accept(packet);
	}

	public int getPlatformIdIndex(long platformId) {
		for (int i = 0; i < platformIds.size(); i++) {
			if (platformIds.get(i).platformId == platformId) {
				return i;
			}
		}
		return -1;
	}

	public boolean containsPlatformId(long platformId) {
		return getPlatformIdIndex(platformId) >= 0;
	}

	public long getFirstPlatformId() {
		return platformIds.isEmpty() ? 0 : platformIds.get(0).platformId;
	}

	public long getLastPlatformId() {
		return platformIds.isEmpty() ? 0 : platformIds.get(platformIds.size() - 1).platformId;
	}

	public String getDestination(int index) {
		for (int i = Math.min(platformIds.size() - 1, index); i >= 0; i--) {
			final String customDestination = platformIds.get(i).customDestination;
			if (Route.destinationIsReset(customDestination)) {
				return null;
			} else if (!customDestination.isEmpty()) {
				return customDestination;
			}
		}
		return null;
	}

	public static boolean destinationIsReset(String destination) {
		return destination.equals("\\r") || destination.equals("\\reset");
	}

	private static void lambda$writePacket$2(FriendlyByteBuf packet, RoutePlatform routePlatform) {
		packet.writeLong(routePlatform.platformId);
		packet.writeUtf(routePlatform.customDestination);
		packet.writeBoolean(routePlatform.stopWithoutOpeningDoors);
		packet.writeBoolean(routePlatform.customDwellTime);
		packet.writeInt(routePlatform.dwellTime);
		packet.writeBoolean(routePlatform.customAdcTime);
		packet.writeInt(routePlatform.adcTime);
	}

	private static void lambda$setPlatformIds$3(FriendlyByteBuf packet, RoutePlatform routePlatform) {
		packet.writeLong(routePlatform.platformId);
		packet.writeUtf(routePlatform.customDestination);
		packet.writeBoolean(routePlatform.stopWithoutOpeningDoors);
		packet.writeBoolean(routePlatform.customDwellTime);
		packet.writeInt(routePlatform.dwellTime);
		packet.writeBoolean(routePlatform.customAdcTime);
		packet.writeInt(routePlatform.adcTime);
	}

	public static class RoutePlatform {

		public String customDestination;
		public boolean stopWithoutOpeningDoors;
		public boolean customDwellTime;
		public int dwellTime;
		public boolean customAdcTime;
		public int adcTime;
		public final long platformId;

		public RoutePlatform(long platformId) {
			this.platformId = platformId;
			customDestination = "";
			stopWithoutOpeningDoors = false;
			customDwellTime = false;
			dwellTime = SavedRailBase.DEFAULT_DWELL_TIME;
			customAdcTime = false;
			adcTime = 0;
		}
	}

	public enum CircularState {NONE, CLOCKWISE, ANTICLOCKWISE}
}
