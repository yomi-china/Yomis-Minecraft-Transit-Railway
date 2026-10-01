package mtr.screen;

import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import mtr.webdashboard.WebDashboardServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class DashboardScreen extends ScreenMapper implements IGui, IPacket {

	private SelectedTab selectedTab;
	private AreaBase editingArea;
	private Route editingRoute;
	private boolean isNew;

	private final TransportMode transportMode;
	private final WidgetMap widgetMap;

	private final Button buttonTabStations;
	private final Button buttonTabRoutes;
	private final Button buttonTabDepots;
	private final Button buttonAddStation;
	private final Button buttonAddRoute;
	private final Button buttonAddDepot;
	private final Button buttonDoneEditingStation;
	private final Button buttonDoneEditingRoute;
	private final Button buttonZoomIn;
	private final Button buttonZoomOut;
	private final Button buttonRailActions;
	private final Button buttonOptions;
	private final Button buttonWebDashboard;

	private final WidgetBetterTextField textFieldName;
	private final WidgetColorSelector colorSelector;

	private final DashboardList dashboardList;

	public static final int MAX_COLOR_ZONE_LENGTH = 6;
	private static final int COLOR_WIDTH = 48;

	/**
	 * Width of the bottom-right strip of map controls: the three text buttons (operations, options
	 * and web dashboard). Each button is {@code SQUARE_SIZE * 3 = 60} wide, which keeps
	 * "Operations..." and "Options..." from spilling into their neighbours in both English and
	 * Chinese. {@code WidgetMap} reads this to leave the strip alone, so that pressing a button never
	 * also drags or area-selects the map underneath it.
	 * <p>
	 * The zoom buttons that used to share this row have moved to the top right, so nothing else
	 * occupies the strip.
	 */
	public static final int BOTTOM_BUTTON_REGION = SQUARE_SIZE * 8;

	/**
	 * Height of the strip reserved at the top right of the map, which holds the coordinate readout
	 * and, directly below it, the two zoom buttons stacked vertically and right aligned.
	 * <p>
	 * This must cover the buttons completely. {@code WidgetMap} is added as the first
	 * {@code GuiEventListener}, so it sees clicks before the buttons do and returns true for anything
	 * inside its own bounds; a reserved strip that stops part way down a button leaves the rest of
	 * that button swallowed by the map and looking dead.
	 * <p>
	 * The readout is drawn at {@code y + TEXT_PADDING} and is {@code TEXT_HEIGHT} tall, the first
	 * button starts one {@code TEXT_FIELD_PADDING} below that, and the two buttons are
	 * {@code SQUARE_SIZE} each.
	 */
	public static final int TOP_BUTTON_REGION = TEXT_PADDING + TEXT_HEIGHT + TEXT_FIELD_PADDING + SQUARE_SIZE * 2;

	/**
	 * Y of the first zoom button's top edge, measured from the top of the screen.
	 * {@code WidgetMap} also uses this to keep its coordinate readout clear of the buttons.
	 */
	public static final int ZOOM_BUTTON_TOP = TEXT_PADDING + TEXT_HEIGHT + TEXT_FIELD_PADDING;

	public DashboardScreen(TransportMode transportMode, boolean useTimeAndWindSync) {
		super(Text.literal(""));
		this.transportMode = transportMode;

		textFieldName = new WidgetBetterTextField(Text.translatable("gui.mtr.name").getString());
		colorSelector = new WidgetColorSelector(this, true, this::toggleButtons);
		widgetMap = new WidgetMap(transportMode, this::onDrawCorners, this::onDrawCornersMouseRelease, this::onClickAddPlatformToRoute, this::onClickEditSavedRail, colorSelector::isMouseOver);

		buttonTabStations = UtilitiesClient.newButton(Text.translatable("gui.mtr.stations"), button -> onSelectTab(SelectedTab.STATIONS));
		buttonTabRoutes = UtilitiesClient.newButton(Text.translatable("gui.mtr.routes"), button -> onSelectTab(SelectedTab.ROUTES));
		buttonTabDepots = UtilitiesClient.newButton(Text.translatable("gui.mtr.depots"), button -> onSelectTab(SelectedTab.DEPOTS));

		buttonAddStation = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_station"), button -> startEditingArea(new Station(), true));
		buttonAddRoute = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_route"), button -> startEditingRoute(new Route(transportMode), true));
		buttonAddDepot = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_depot"), button -> startEditingArea(new Depot(transportMode), true));
		buttonDoneEditingStation = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> onDoneEditingArea());
		buttonDoneEditingRoute = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> onDoneEditingRoute());
		buttonZoomIn = UtilitiesClient.newButton(Text.literal("+"), button -> widgetMap.scale(1));
		buttonZoomOut = UtilitiesClient.newButton(Text.literal("-"), button -> widgetMap.scale(-1));
		buttonRailActions = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_actions_button"), button -> {
			if (minecraft != null) {
				UtilitiesClient.setScreen(minecraft, new RailActionsScreen());
			}
		});
		buttonOptions = UtilitiesClient.newButton(Text.translatable("menu.options"), button -> {
			if (minecraft != null) {
				UtilitiesClient.setScreen(minecraft, new ConfigScreen(useTimeAndWindSync));
			}
		});
		// Not gated on ClientData.hasPermission(): the page itself decides what the visitor may do,
		// and a player without edit rights still benefits from the read-only view.
		buttonWebDashboard = UtilitiesClient.newButton(Text.translatable("gui.mtr.web_dashboard"), button -> WebDashboardServer.openInBrowser());

		dashboardList = new DashboardList(this::onFind, this::onDrawArea, this::onEdit, this::onSort, null, this::onDelete, this::getList, () -> ClientData.DASHBOARD_SEARCH, text -> ClientData.DASHBOARD_SEARCH = text);

		onSelectTab(SelectedTab.STATIONS);
	}

	@Override
	protected void init() {
		super.init();

		final int tabCount = 3;
		final int bottomRowY = height - SQUARE_SIZE;

		widgetMap.setPositionAndSize(PANEL_WIDTH, 0, width - PANEL_WIDTH, height);

		IDrawing.setPositionAndWidth(buttonTabStations, 0, 0, PANEL_WIDTH / tabCount);
		IDrawing.setPositionAndWidth(buttonTabRoutes, PANEL_WIDTH / tabCount, 0, PANEL_WIDTH / tabCount);
		IDrawing.setPositionAndWidth(buttonTabDepots, 2 * PANEL_WIDTH / tabCount, 0, PANEL_WIDTH / tabCount);
		IDrawing.setPositionAndWidth(buttonAddStation, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonAddRoute, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonAddDepot, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonDoneEditingStation, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonDoneEditingRoute, 0, bottomRowY, PANEL_WIDTH);
		// The zoom buttons are stacked vertically at the top right, directly below the coordinate
		// readout that WidgetMap draws, and right aligned with it.
		IDrawing.setPositionAndWidth(buttonZoomIn, width - SQUARE_SIZE, ZOOM_BUTTON_TOP, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonZoomOut, width - SQUARE_SIZE, ZOOM_BUTTON_TOP + SQUARE_SIZE, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonRailActions, width - SQUARE_SIZE * 9, bottomRowY, SQUARE_SIZE * 3);
		IDrawing.setPositionAndWidth(buttonOptions, width - SQUARE_SIZE * 6, bottomRowY, SQUARE_SIZE * 3);
		IDrawing.setPositionAndWidth(buttonWebDashboard, width - SQUARE_SIZE * 3, bottomRowY, SQUARE_SIZE * 3);

		IDrawing.setPositionAndWidth(textFieldName, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, PANEL_WIDTH - COLOR_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(colorSelector, PANEL_WIDTH - COLOR_WIDTH + TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, COLOR_WIDTH - TEXT_FIELD_PADDING);

		dashboardList.x = 0;
		dashboardList.y = SQUARE_SIZE;
		dashboardList.width = PANEL_WIDTH;

		toggleButtons();
		dashboardList.init(this::addDrawableChild);
		addWidget(widgetMap);

		addDrawableChild(buttonTabStations);
		addDrawableChild(buttonTabRoutes);
		addDrawableChild(buttonTabDepots);
		addDrawableChild(buttonAddStation);
		addDrawableChild(buttonAddRoute);
		addDrawableChild(buttonAddDepot);
		addDrawableChild(buttonDoneEditingStation);
		addDrawableChild(buttonDoneEditingRoute);
		addDrawableChild(buttonZoomIn);
		addDrawableChild(buttonZoomOut);
		addDrawableChild(buttonRailActions);
		addDrawableChild(buttonOptions);
		addDrawableChild(buttonWebDashboard);

		addDrawableChild(textFieldName);
		addDrawableChild(colorSelector);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(guiGraphics);
			widgetMap.render(guiGraphics, mouseX, mouseY, delta);
			guiGraphics.pose().pushPose();
			guiGraphics.pose().translate(0, 0, 500);
			guiGraphics.fill(0, 0, PANEL_WIDTH, height, ARGB_BACKGROUND);
			dashboardList.render(guiGraphics, font);
			super.render(guiGraphics, mouseX, mouseY, delta);
			guiGraphics.pose().popPose();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		dashboardList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		dashboardList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void tick() {
		textFieldName.tick();
		dashboardList.tick();

		try {
			switch (selectedTab) {
				case STATIONS:
					if (editingArea == null) {
						dashboardList.setData(ClientData.STATIONS, true, true, true, false, false, true);
					} else {
						final Map<Long, Platform> platformData = ClientData.DATA_CACHE.requestStationIdToPlatforms(editingArea.id);
						dashboardList.setData(platformData == null ? new ArrayList<>() : new ArrayList<>(platformData.values()), true, false, true, false, false, false);
					}
					break;
				case ROUTES:
					if (editingRoute == null) {
						dashboardList.setData(ClientData.getFilteredDataSet(transportMode, ClientData.ROUTES), false, true, true, false, false, true);
					} else {
						final List<DataConverter> routeData = editingRoute.platformIds.stream().map(platformId -> {
							final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId.platformId);
							if (platform == null) {
								return null;
							} else {
								final String customDestinationPrefix = platformId.customDestination.isEmpty() ? "" : Route.destinationIsReset(platformId.customDestination) ? "\"" : "*";
								final Station station = ClientData.DATA_CACHE.platformIdToStation.get(platform.id);
								if (station != null) {
									return new DataConverter(String.format("%s%s (%s)", customDestinationPrefix, station.name, platform.name), station.color);
								} else {
									return new DataConverter(String.format("%s(%s)", customDestinationPrefix, platform.name), 0);
								}
							}
						}).filter(Objects::nonNull).collect(Collectors.toList());
						dashboardList.setData(routeData, false, false, true, true, false, true);
					}
					break;
				case DEPOTS:
					if (editingArea == null) {
						dashboardList.setData(ClientData.getFilteredDataSet(transportMode, ClientData.DEPOTS), true, true, true, false, false, true);
					} else {
						final Map<Long, Siding> sidingData = ClientData.DATA_CACHE.requestDepotIdToSidings(editingArea.id);
						dashboardList.setData(sidingData == null ? new ArrayList<>() : new ArrayList<>(sidingData.values()), true, false, true, false, false, false);
					}
					break;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void onSelectTab(SelectedTab tab) {
		selectedTab = tab;
		buttonTabStations.active = tab != SelectedTab.STATIONS;
		buttonTabRoutes.active = tab != SelectedTab.ROUTES;
		buttonTabDepots.active = tab != SelectedTab.DEPOTS;
		stopEditing();
		widgetMap.setShowStations(selectedTab != SelectedTab.DEPOTS);
	}

	private void onFind(NameColorDataBase data, int index) {
		if (selectedTab == SelectedTab.STATIONS || selectedTab == SelectedTab.DEPOTS) {
			if (editingArea == null && data instanceof AreaBase) {
				final AreaBase area = (AreaBase) data;
				if (AreaBase.nonNullCorners(area)) {
					widgetMap.find(area.corner1.getA(), area.corner1.getB(), area.corner2.getA(), area.corner2.getB());
				}
			} else if (selectedTab == SelectedTab.STATIONS) {
				final Platform platform = (Platform) data;
				widgetMap.find(platform.getMidPos());
			}
		}
	}

	private void onDrawArea(NameColorDataBase data, int index) {
		switch (selectedTab) {
			case STATIONS:
			case DEPOTS:
				if (editingArea == null && data instanceof AreaBase) {
					startEditingArea((AreaBase) data, false);
				}
				break;
			case ROUTES:
				if (data instanceof Route) {
					startEditingRoute((Route) data, false);
				}
				break;
		}
		dashboardList.clearSearch();
	}

	private void onEdit(NameColorDataBase data, int index) {
		if (minecraft != null) {
			switch (selectedTab) {
				case STATIONS:
					if (editingArea == null) {
						if (data instanceof Station) {
							UtilitiesClient.setScreen(minecraft, new EditStationScreen((Station) data, this));
						}
					} else {
						if (data instanceof Platform) {
							UtilitiesClient.setScreen(minecraft, new PlatformScreen((Platform) data, transportMode, this));
						}
					}
					break;
				case ROUTES:
					if (editingRoute == null && data instanceof Route) {
						UtilitiesClient.setScreen(minecraft, new EditRouteScreen((Route) data, this));
					} else if (editingRoute != null && index >= 0 && index < editingRoute.platformIds.size()) {
						final Route.RoutePlatform rp = editingRoute.platformIds.get(index);
						final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(rp.platformId);
						final Station station = ClientData.DATA_CACHE.platformIdToStation.get(platform != null ? platform.id : 0);
						final String stationName = station != null ? station.name : "?";
						final String platformName = platform != null ? platform.name : "?";
						UtilitiesClient.setScreen(minecraft, new RoutePlatformScreen(
								editingRoute, index,
								editingRoute.name,
								stationName, platformName,
								editingRoute.color,
								() -> editingRoute.setPlatformIds(packet ->
										PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet)),
								this
						));
					}
					break;
				case DEPOTS:
					if (editingArea == null) {
						if (data instanceof Depot) {
							UtilitiesClient.setScreen(minecraft, new EditDepotScreen((Depot) data, transportMode, this));
						}
					} else {
						if (data instanceof Siding) {
							UtilitiesClient.setScreen(minecraft, new SidingScreen((Siding) data, transportMode, this));
						}
					}
					break;
			}
		}
	}

	private void onSort() {
		if (selectedTab == SelectedTab.ROUTES && editingRoute != null) {
			editingRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		}
	}

	private void onDelete(NameColorDataBase data, int index) {
		try {
			switch (selectedTab) {
				case STATIONS:
					if (minecraft != null) {
						final Station station = (Station) data;
						UtilitiesClient.setScreen(minecraft, new DeleteConfirmationScreen(() -> {
							PacketTrainDataGuiClient.sendDeleteData(PACKET_DELETE_STATION, station.id);
							ClientData.STATIONS.remove(station);
						}, IGui.formatStationName(station.name), this));
					}
					break;
				case ROUTES:
					if (editingRoute == null) {
						if (minecraft != null && data instanceof Route) {
							final Route route = (Route) data;
							UtilitiesClient.setScreen(minecraft, new DeleteConfirmationScreen(() -> {
								PacketTrainDataGuiClient.sendDeleteData(PACKET_DELETE_ROUTE, route.id);
								ClientData.ROUTES.remove(route);
							}, IGui.formatStationName(route.name), this));
						}
					} else {
						editingRoute.platformIds.remove(index);
						editingRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
					}
					break;
				case DEPOTS:
					if (minecraft != null && data instanceof Depot) {
						final Depot depot = (Depot) data;
						UtilitiesClient.setScreen(minecraft, new DeleteConfirmationScreen(() -> {
							PacketTrainDataGuiClient.sendDeleteData(PACKET_DELETE_DEPOT, depot.id);
							ClientData.DEPOTS.remove(depot);
						}, IGui.formatStationName(depot.name), this));
					}
					break;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private List<Route.RoutePlatform> getList() {
		return editingRoute == null ? new ArrayList<>() : editingRoute.platformIds;
	}

	private void startEditingArea(AreaBase editingArea, boolean isNew) {
		this.editingArea = editingArea;
		editingRoute = null;
		this.isNew = isNew;

		textFieldName.setValue(editingArea.name);
		colorSelector.setColor(editingArea.color);

		widgetMap.startEditingArea(editingArea);
		toggleButtons();
	}

	private void startEditingRoute(Route editingRoute, boolean isNew) {
		editingArea = null;
		this.editingRoute = editingRoute;
		this.isNew = isNew;

		textFieldName.setValue(editingRoute.name);
		colorSelector.setColor(editingRoute.color);

		widgetMap.startEditingRoute();
		toggleButtons();
	}

	private void onDrawCorners(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2) {
		editingArea.corner1 = corner1;
		editingArea.corner2 = corner2;
		toggleButtons();
	}

	private void onDrawCornersMouseRelease() {
		editingArea.setCorners(packet -> PacketTrainDataGuiClient.sendUpdate(editingArea instanceof Station ? PACKET_UPDATE_STATION : PACKET_UPDATE_DEPOT, packet));
	}

	private void onClickAddPlatformToRoute(long platformId) {
		editingRoute.platformIds.add(new Route.RoutePlatform(platformId));
		editingRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
	}

	private void onClickEditSavedRail(SavedRailBase savedRail) {
		if (savedRail instanceof Platform) {
			UtilitiesClient.setScreen(Minecraft.getInstance(), new PlatformScreen((Platform) savedRail, transportMode, this));
		} else if (savedRail instanceof Siding) {
			UtilitiesClient.setScreen(Minecraft.getInstance(), new SidingScreen((Siding) savedRail, transportMode, this));
		}
	}

	private void onDoneEditingArea() {
		if (editingArea instanceof Station || editingArea instanceof Depot) {
			final boolean isStation = editingArea instanceof Station;
			if (isNew) {
				if (isStation) {
					ClientData.STATIONS.add((Station) editingArea);
				} else {
					ClientData.DEPOTS.add((Depot) editingArea);
				}
			}
			editingArea.name = IGui.textOrUntitled(textFieldName.getValue());
			editingArea.color = colorSelector.getColor();
			editingArea.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(isStation ? PACKET_UPDATE_STATION : PACKET_UPDATE_DEPOT, packet));
		}
		stopEditing();
	}

	private void onDoneEditingRoute() {
		if (isNew) {
			try {
				ClientData.ROUTES.add(editingRoute);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		editingRoute.name = IGui.textOrUntitled(textFieldName.getValue());
		editingRoute.color = colorSelector.getColor();
		editingRoute.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		stopEditing();
	}

	private void stopEditing() {
		editingArea = null;
		editingRoute = null;
		widgetMap.stopEditing();
		toggleButtons();
	}

	private void toggleButtons() {
		final boolean hasPermission = ClientData.hasPermission();

		buttonAddStation.visible = selectedTab == SelectedTab.STATIONS && editingArea == null && hasPermission;
		buttonAddRoute.visible = selectedTab == SelectedTab.ROUTES && editingRoute == null && hasPermission;
		buttonAddDepot.visible = selectedTab == SelectedTab.DEPOTS && editingArea == null && hasPermission;
		buttonDoneEditingStation.visible = (selectedTab == SelectedTab.STATIONS || selectedTab == SelectedTab.DEPOTS) && editingArea != null;
		buttonDoneEditingStation.active = AreaBase.nonNullCorners(editingArea);
		buttonDoneEditingRoute.visible = selectedTab == SelectedTab.ROUTES && editingRoute != null;

		final boolean showTextFields = ((selectedTab == SelectedTab.STATIONS || selectedTab == SelectedTab.DEPOTS) && editingArea != null) || (selectedTab == SelectedTab.ROUTES && editingRoute != null);
		textFieldName.visible = showTextFields;
		colorSelector.visible = showTextFields;
		dashboardList.height = height - SQUARE_SIZE * 2 - (showTextFields ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0);
	}

	private enum SelectedTab {STATIONS, ROUTES, DEPOTS}
}
