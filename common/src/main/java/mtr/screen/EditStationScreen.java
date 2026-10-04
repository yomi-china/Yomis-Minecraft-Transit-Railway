package mtr.screen;

import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.DataConverter;
import mtr.data.NameColorDataBase;
import mtr.data.Route;
import mtr.data.Station;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.Util;
import mtr.mappings.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class EditStationScreen extends EditNameColorScreenBase<Station> {

    String editingExit;
    int editingDestinationIndex;
    int clickDelay;

    private final Component stationZoneText = Text.translatable("gui.mtr.zone");
    private final Component exitParentsText = Text.translatable("gui.mtr.exit_parents");
    private final Component exitDestinationsText = Text.translatable("gui.mtr.exit_destinations");

    private final WidgetBetterTextField textFieldZone;
    private final WidgetBetterTextField textFieldExitParentLetter;
    private final WidgetBetterTextField textFieldExitParentNumber;
    private final WidgetBetterTextField textFieldExitDestination;

    private final Button buttonAddExitParent;
    private final Button buttonDoneExitParent;
    private final Button buttonAddExitDestination;
    private final Button buttonDoneExitDestination;

    private final DashboardList exitParentList;
    private final DashboardList exitDestinationList;

    private long startTime;

    private float routeScrollVisual = 0;
    private float routeScrollFrom = 0;
    private float routeScrollTarget = 0;
    private long routeScrollTime = 0;
    private List<Route> cachedStationRoutes = new ArrayList<>();
    private long cachedStationId = -1;

    private static final float MAX_TOP_WIDTH_RATIO = 0.49f;
    private static final float ROUTE_SCROLL_SPEED = 32;
    private static final float ROUTE_SCROLL_ANIM = 0.18f;
    private static final int ROUTE_RECT_GAP = 2;
    private static final int ROUTE_RECT_PAD_X = 5;
    private static final int ROUTE_RECT_PAD_Y = 3;
    private static final int ROUTE_LINE_HEIGHT = 10;
    private static final int ROUTE_MIN_RECT_WIDTH = 36;
    private static final int HEADER_H = 40;
    private static final int PAD = 14;
    private static final int EXIT_PANELS_START = 96;
    private static final int RIGHT_PADDING = 10; // 右侧滚动留白

    public EditStationScreen(Station station, DashboardScreen dashboardScreen) {
        super(station, dashboardScreen, "gui.mtr.station_name", "gui.mtr.station_color");
        textFieldZone = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, "", DashboardScreen.MAX_COLOR_ZONE_LENGTH);
        textFieldExitParentLetter = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.LETTER, "A", 1);
        textFieldExitParentNumber = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, "1", 2);
        textFieldExitDestination = new WidgetBetterTextField("");
        buttonAddExitParent = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_exit"), button -> checkClickDelay(() -> changeEditingExit("", -1)));
        buttonDoneExitParent = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> checkClickDelay(this::onDoneExitParent));
        buttonAddExitDestination = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_exit_destination"), button -> checkClickDelay(() -> changeEditingExit(editingExit, station.exits.containsKey(editingExit) ? station.exits.get(editingExit).size() : -1)));
        buttonDoneExitDestination = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> checkClickDelay(this::onDoneExitDestination));
        exitParentList = new DashboardList(null, null, this::onEditExitParent, null, null, this::onDeleteExitParent, null, () -> ClientData.EXIT_PARENTS_SEARCH, text -> ClientData.EXIT_PARENTS_SEARCH = text);
        exitDestinationList = new DashboardList(null, null, this::onEditExitDestination, this::onSortExitDestination, null, this::onDeleteExitDestination, this::getExitDestinationList, () -> ClientData.EXIT_DESTINATIONS_SEARCH, text -> ClientData.EXIT_DESTINATIONS_SEARCH = text);
    }

    @Override
    protected void init() {
        startTime = Util.getMillis();

        setPositionsAndInit(0, width / 2, width / 4 * 3);

        final int yFields = HEADER_H + 20;
        UtilitiesClient.setWidgetY(textFieldName, yFields);
        UtilitiesClient.setWidgetY(colorSelector, yFields);

        final int zoneX = width / 4 * 3 + TEXT_FIELD_PADDING / 2;
        final int zoneW = width / 4 - TEXT_FIELD_PADDING;
        IDrawing.setPositionAndWidth(textFieldZone, zoneX, yFields, zoneW);

        final int yExitText = height - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING / 2;
        IDrawing.setPositionAndWidth(textFieldExitParentLetter, TEXT_FIELD_PADDING / 2, yExitText, width / 4 - TEXT_FIELD_PADDING);
        IDrawing.setPositionAndWidth(textFieldExitParentNumber, TEXT_FIELD_PADDING / 2 + width / 4, yExitText, width / 4 - TEXT_FIELD_PADDING);
        IDrawing.setPositionAndWidth(textFieldExitDestination, width / 2 + TEXT_FIELD_PADDING / 2, yExitText, width / 2 - TEXT_FIELD_PADDING);

        IDrawing.setPositionAndWidth(buttonAddExitParent, 0, height - SQUARE_SIZE, width / 2);
        IDrawing.setPositionAndWidth(buttonDoneExitParent, 0, height - SQUARE_SIZE, width / 2);
        IDrawing.setPositionAndWidth(buttonAddExitDestination, width / 2, height - SQUARE_SIZE, width / 2);
        IDrawing.setPositionAndWidth(buttonDoneExitDestination, width / 2, height - SQUARE_SIZE, width / 2);

        textFieldZone.setValue(String.valueOf(data.zone));

        exitParentList.x = 0;
        exitParentList.y = EXIT_PANELS_START;
        exitParentList.height = height - EXIT_PANELS_START - SQUARE_SIZE;
        exitParentList.width = width / 2;

        exitDestinationList.x = width / 2;
        exitDestinationList.y = EXIT_PANELS_START;
        exitDestinationList.height = height - EXIT_PANELS_START - SQUARE_SIZE;
        exitDestinationList.width = width / 2;

        exitParentList.init(this::addDrawableChild);
        exitDestinationList.init(this::addDrawableChild);

        addDrawableChild(textFieldZone);
        addDrawableChild(textFieldExitParentLetter);
        addDrawableChild(textFieldExitParentNumber);
        addDrawableChild(textFieldExitDestination);
        addDrawableChild(buttonAddExitParent);
        addDrawableChild(buttonDoneExitParent);
        addDrawableChild(buttonAddExitDestination);
        addDrawableChild(buttonDoneExitDestination);

        changeEditingExit(null, -1);
    }

    @Override
    public void tick() {
        super.tick();

        if (clickDelay > 0) clickDelay--;

        textFieldZone.tick();
        textFieldExitParentLetter.tick();
        textFieldExitParentNumber.tick();
        textFieldExitDestination.tick();
        exitParentList.tick();
        exitDestinationList.tick();

        final List<DataConverter> exitParents = data.exits.keySet().stream().sorted().map(value -> {
            final List<String> destinations = data.exits.get(value);
            final String additional = destinations.size() > 1 ? "(+" + (destinations.size() - 1) + ")" : "";
            return new DataConverter(destinations.size() > 0 ? value + "|" + destinations.get(0) + "|" + additional : value, 0);
        }).collect(Collectors.toList());
        exitParentList.setData(exitParents, false, false, true, false, false, true);

        final List<DataConverter> exitDestinations = parentExists()
                ? data.exits.get(editingExit).stream().map(value -> new DataConverter(value, 0)).collect(Collectors.toList())
                : new ArrayList<>();
        exitDestinationList.setData(exitDestinations, false, false, true, true, false, true);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        guiGraphics.fill(0, 0, width, height, 0xE6101010);
        final int currentColor = colorSelector.getColor();
        final float elapsed = (Util.getMillis() - startTime) / 1000f;
        final float animT = Math.min(1, elapsed / 0.3f);
        final float eased = easeOutCubic(animT);
        final int maxTopWidth = (int) (width * MAX_TOP_WIDTH_RATIO);
        final int currentTopWidth = (int) (eased * maxTopWidth);

        final List<Route> stationRoutes = getStationRoutes();
        if (!stationRoutes.isEmpty()) {
            final List<Integer> rectWidths = new ArrayList<>(stationRoutes.size());
            final List<List<String>> routeNames = new ArrayList<>(stationRoutes.size());
            float totalWidth = 0;
            for (final Route route : stationRoutes) {
                final List<String> lines = splitRouteDisplayName(route);
                routeNames.add(lines);
                int maxLineW = ROUTE_MIN_RECT_WIDTH;
                for (final String line : lines) {
                    maxLineW = Math.max(maxLineW, font.width(line));
                }
                final int rectW = maxLineW + ROUTE_RECT_PAD_X * 2;
                rectWidths.add(rectW);
                totalWidth += rectW + ROUTE_RECT_GAP;
            }
            totalWidth -= ROUTE_RECT_GAP;

            final float availableWidth = width - maxTopWidth;
            final float maxScroll = Math.min(0, availableWidth - totalWidth - RIGHT_PADDING);
            routeScrollTarget = Math.max(maxScroll, Math.min(0, routeScrollTarget));

            if (routeScrollTime == 0) {
                routeScrollVisual = routeScrollTarget;
            } else {
                final long now = Util.getMillis();
                final float animElapsed = (now - routeScrollTime) / 1000f;
                final float progress = Math.min(1, animElapsed / ROUTE_SCROLL_ANIM);
                routeScrollVisual = routeScrollFrom + (routeScrollTarget - routeScrollFrom) * easeOutCubic(progress);
            }

            float xOff = maxTopWidth + routeScrollVisual;

            guiGraphics.enableScissor(maxTopWidth, 0, width, HEADER_H);

            for (int i = 0; i < stationRoutes.size(); i++) {
                final Route route = stationRoutes.get(i);
                final int rectW = rectWidths.get(i);
                final int bgColor = route.color;
                final boolean light = isColorLight(bgColor);
                final int txtColor = light ? ARGB_BLACK : ARGB_WHITE;
                final int fillColor = (bgColor & 0x00FFFFFF) | 0xD0000000;

                final int rectBottom = HEADER_H - ROUTE_RECT_PAD_Y;
                guiGraphics.fill((int) xOff, ROUTE_RECT_PAD_Y, (int) (xOff + rectW), rectBottom, fillColor);

                final List<String> lines = routeNames.get(i);
                final int totalTextH = lines.size() * ROUTE_LINE_HEIGHT;
                int lineY = (HEADER_H - totalTextH) / 2;
                for (int li = 0; li < lines.size(); li++) {
                    final String text = lines.get(li);
                    final int textW = font.width(text);
                    final int textX = (int) (xOff + (rectW - textW) / 2);
                    if (li > 0) {
                        final float scale = 0.75f;
                        guiGraphics.pose().pushPose();
                        guiGraphics.pose().translate(textX, lineY, 0);
                        guiGraphics.pose().scale(scale, scale, 1);
                        guiGraphics.drawString(font, text, 0, 0, txtColor);
                        guiGraphics.pose().popPose();
                        lineY += (int) (ROUTE_LINE_HEIGHT * scale);
                    } else {
                        guiGraphics.drawString(font, text, textX, lineY, txtColor);
                        lineY += ROUTE_LINE_HEIGHT;
                    }
                }
                xOff += rectW + ROUTE_RECT_GAP;
            }

            guiGraphics.disableScissor();
        }

        if (currentTopWidth > 0) {
            final int solidColor = currentColor | 0xFF000000;
            guiGraphics.fill(0, 0, currentTopWidth, HEADER_H, solidColor);
        }

        final int textColor = isColorLight(currentColor) ? 0xFF000000 : 0xFFFFFFFF;
        guiGraphics.drawString(font, Text.translatable("gui.mtr.station_name"), PAD, 6,
                (textColor & 0x00FFFFFF) | 0x80000000);
        guiGraphics.drawString(font, data.name, PAD, 22, textColor);

        final int labelY = HEADER_H + 6;
        guiGraphics.drawCenteredString(font, nameText, (width / 2) / 2, labelY, 0xFFAAAAAA);
        guiGraphics.drawCenteredString(font, colorText, (width / 2 + width / 4 * 3) / 2, labelY, 0xFFAAAAAA);
        guiGraphics.drawCenteredString(font, stationZoneText, width / 8 * 7, labelY, 0xFFAAAAAA);
        guiGraphics.vLine(width / 2, EXIT_PANELS_START, height, ARGB_WHITE_TRANSLUCENT);
        exitParentList.render(guiGraphics, font);
        exitDestinationList.render(guiGraphics, font);
        guiGraphics.drawCenteredString(font, exitParentsText, width / 4,
                EXIT_PANELS_START - SQUARE_SIZE + TEXT_PADDING, ARGB_WHITE);
        if (parentExists()) {
            guiGraphics.drawCenteredString(font, exitDestinationsText, 3 * width / 4,
                    EXIT_PANELS_START - SQUARE_SIZE + TEXT_PADDING, ARGB_WHITE);
        }
        super.render(guiGraphics, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        exitParentList.mouseMoved(mouseX, mouseY);
        exitDestinationList.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        exitParentList.mouseScrolled(mouseX, mouseY, amount);
        exitDestinationList.mouseScrolled(mouseX, mouseY, amount);
        if (mouseY < HEADER_H && mouseX >= width * MAX_TOP_WIDTH_RATIO) {
            routeScrollFrom = routeScrollVisual;
            routeScrollTarget += (float) (amount * ROUTE_SCROLL_SPEED);
            routeScrollTime = Util.getMillis();
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    protected void saveData() {
        super.saveData();
        try {
            data.zone = Integer.parseInt(textFieldZone.getValue());
        } catch (Exception ignored) {
            data.zone = 0;
        }
        data.setZone(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
    }

    private void changeEditingExit(String editingExit, int editingDestinationIndex) {
        this.editingExit = editingExit;
        this.editingDestinationIndex = parentExists() ? editingDestinationIndex : -1;

        if (editingExit != null) {
            textFieldExitParentLetter.setValue(editingExit.toUpperCase(Locale.ENGLISH).replaceAll("[^A-Z]", ""));
            textFieldExitParentNumber.setValue(editingExit.replaceAll("\\D", ""));
        }
        if (editingDestinationIndex >= 0 && editingDestinationIndex < data.exits.get(editingExit).size()) {
            textFieldExitDestination.setValue(data.exits.get(editingExit).get(editingDestinationIndex));
        } else {
            textFieldExitDestination.setValue("");
        }

        textFieldExitParentLetter.visible = editingExit != null;
        textFieldExitParentNumber.visible = editingExit != null;
        textFieldExitDestination.visible = editingDestinationIndex >= 0;
        buttonAddExitParent.visible = editingExit == null;
        buttonDoneExitParent.visible = editingExit != null;
        buttonAddExitDestination.visible = parentExists() && editingDestinationIndex < 0;
        buttonDoneExitDestination.visible = editingDestinationIndex >= 0;
        exitDestinationList.x = parentExists() ? width / 2 : width;
        exitParentList.height = height - EXIT_PANELS_START - (editingExit == null ? SQUARE_SIZE : SQUARE_SIZE * 2 + TEXT_FIELD_PADDING);
        exitDestinationList.height = height - EXIT_PANELS_START - (editingDestinationIndex >= 0 ? SQUARE_SIZE * 2 + TEXT_FIELD_PADDING : SQUARE_SIZE);
    }

    private void onDoneExitParent() {
        final String parentLetter = textFieldExitParentLetter.getValue();
        final String parentNumber = textFieldExitParentNumber.getValue();
        if (!parentNumber.isEmpty()) {
            try {
                final String exitParent = parentLetter.isEmpty() ? String.valueOf(Integer.parseInt(parentNumber)) : parentLetter + Integer.parseInt(parentNumber);
                data.setExitParent(editingExit, exitParent, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        changeEditingExit(null, -1);
    }

    private void onDoneExitDestination() {
        final String destination = textFieldExitDestination.getValue();
        if (parentExists() && editingDestinationIndex >= 0 && !destination.isEmpty()) {
            final List<String> destinations = data.exits.get(editingExit);
            if (editingDestinationIndex < destinations.size()) {
                destinations.set(editingDestinationIndex, destination);
            } else {
                destinations.add(destination);
            }
            data.setExitDestinations(editingExit, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
        }
        changeEditingExit(editingExit, -1);
    }

    private void onEditExitParent(NameColorDataBase listData, int index) {
        changeEditingExit(formatExitName(listData.name), -1);
    }

    private void onDeleteExitParent(NameColorDataBase listData, int index) {
        data.deleteExitParent(formatExitName(listData.name), packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
        changeEditingExit(null, -1);
    }

    private void onEditExitDestination(NameColorDataBase listData, int index) {
        changeEditingExit(editingExit, index);
    }

    private void onSortExitDestination() {
        data.setExitDestinations(editingExit, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
        changeEditingExit(editingExit, -1);
    }

    private void onDeleteExitDestination(NameColorDataBase listData, int index) {
        if (parentExists()) {
            data.exits.get(editingExit).remove(listData.name);
            data.setExitDestinations(editingExit, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
        }
        changeEditingExit(editingExit, -1);
    }

    private List<String> getExitDestinationList() {
        return parentExists() ? data.exits.get(editingExit) : new ArrayList<>();
    }

    private void checkClickDelay(Runnable callback) {
        if (clickDelay == 0) {
            callback.run();
            clickDelay = 10;
        }
    }

    private List<Route> getStationRoutes() {
        if (data.id != cachedStationId) {
            cachedStationId = data.id;
            cachedStationRoutes.clear();
            for (final Route route : ClientData.ROUTES) {
                if (!route.isHidden) {
                    for (final Route.RoutePlatform rp : route.platformIds) {
                        final Station platformStation = ClientData.DATA_CACHE.platformIdToStation.get(rp.platformId);
                        if (platformStation != null && platformStation.id == data.id) {
                            cachedStationRoutes.add(route);
                            break;
                        }
                    }
                }
            }
        }
        return cachedStationRoutes;
    }

    private static List<String> splitRouteDisplayName(Route route) {
        final List<String> lines = new ArrayList<>();
        final String[] doubleSplit = route.name.split("\\|\\|", 2);
        final String namePart = doubleSplit[0].trim();
        final String tagPart = doubleSplit.length > 1 ? doubleSplit[1].trim() : "";

        if (!namePart.isEmpty()) {
            final String[] nameSplits = namePart.split("\\|");
            lines.add(nameSplits[0]);
            if (nameSplits.length > 1) {
                final StringBuilder other = new StringBuilder();
                for (int i = 1; i < nameSplits.length; i++) {
                    if (other.length() > 0) {
                        other.append(" ");
                    }
                    other.append(nameSplits[i]);
                }
                lines.add(other.toString());
            }
        }
        if (!tagPart.isEmpty()) {
            lines.add(tagPart);
        }
        return lines;
    }

    private boolean parentExists() {
        return editingExit != null && data.exits.containsKey(editingExit);
    }

    private static String formatExitName(String text) {
        return text.split("\\|")[0];
    }

    private static float easeOutCubic(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static boolean isColorLight(int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 140;
    }
}
