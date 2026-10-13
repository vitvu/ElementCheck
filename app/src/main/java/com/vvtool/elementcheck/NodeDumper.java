package com.vvtool.elementcheck;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Walks every interactive window and serialises the full accessibility tree to JSON.
 * Keeps the live AccessibilityNodeInfo objects (by flat index) so the web UI can act on them.
 */
final class NodeDumper {
    private static final int MAX_NODES = 8000;
    private static final int MAX_DEPTH = 150;

    private static final SparseArray<String> ACTION_NAMES = new SparseArray<>();
    static {
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_FOCUS, "focus");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, "clearFocus");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_SELECT, "select");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION, "clearSelection");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_CLICK, "click");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_LONG_CLICK, "longClick");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, "a11yFocus");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, "clearA11yFocus");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, "nextAtGranularity");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, "prevAtGranularity");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT, "nextHtml");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT, "prevHtml");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scrollForward");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "scrollBackward");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_COPY, "copy");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_PASTE, "paste");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_CUT, "cut");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_SET_SELECTION, "setSelection");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_EXPAND, "expand");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_COLLAPSE, "collapse");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_DISMISS, "dismiss");
        ACTION_NAMES.put(AccessibilityNodeInfo.ACTION_SET_TEXT, "setText");
        ACTION_NAMES.put(android.R.id.accessibilityActionShowOnScreen, "showOnScreen");
        ACTION_NAMES.put(android.R.id.accessibilityActionScrollToPosition, "scrollToPosition");
        ACTION_NAMES.put(android.R.id.accessibilityActionScrollUp, "scrollUp");
        ACTION_NAMES.put(android.R.id.accessibilityActionScrollLeft, "scrollLeft");
        ACTION_NAMES.put(android.R.id.accessibilityActionScrollDown, "scrollDown");
        ACTION_NAMES.put(android.R.id.accessibilityActionScrollRight, "scrollRight");
        ACTION_NAMES.put(android.R.id.accessibilityActionContextClick, "contextClick");
        if (Build.VERSION.SDK_INT >= 24) {
            ACTION_NAMES.put(android.R.id.accessibilityActionSetProgress, "setProgress");
        }
        if (Build.VERSION.SDK_INT >= 26) {
            ACTION_NAMES.put(android.R.id.accessibilityActionMoveWindow, "moveWindow");
        }
        if (Build.VERSION.SDK_INT >= 28) {
            ACTION_NAMES.put(android.R.id.accessibilityActionShowTooltip, "showTooltip");
            ACTION_NAMES.put(android.R.id.accessibilityActionHideTooltip, "hideTooltip");
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ACTION_NAMES.put(android.R.id.accessibilityActionPageUp, "pageUp");
            ACTION_NAMES.put(android.R.id.accessibilityActionPageDown, "pageDown");
            ACTION_NAMES.put(android.R.id.accessibilityActionPageLeft, "pageLeft");
            ACTION_NAMES.put(android.R.id.accessibilityActionPageRight, "pageRight");
        }
        if (Build.VERSION.SDK_INT >= 30) {
            ACTION_NAMES.put(android.R.id.accessibilityActionPressAndHold, "pressAndHold");
            ACTION_NAMES.put(android.R.id.accessibilityActionImeEnter, "imeEnter");
        }
    }

    final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    private final Json j = new Json(64 * 1024);
    private final Rect r = new Rect();
    private final String ownPackage;

    private NodeDumper(String ownPackage) { this.ownPackage = ownPackage; }

    /** Result of a dump: JSON for "windows" array plus the live nodes, indexed by "i". */
    static final class Result {
        String windowsJson;
        String topPackage;
        List<AccessibilityNodeInfo> nodes;
    }

    static Result dump(AccessibilityService svc) {
        NodeDumper d = new NodeDumper(svc.getPackageName());
        String top = d.dumpWindows(svc);
        Result res = new Result();
        res.windowsJson = d.j.toString();
        res.topPackage = top;
        res.nodes = d.nodes;
        return res;
    }

    private String dumpWindows(AccessibilityService svc) {
        String topPkg = null;
        HashMap<String, Integer> rootClassCount = new HashMap<>();
        j.beginArr();
        List<AccessibilityWindowInfo> windows = null;
        try { windows = svc.getWindows(); } catch (Exception ignored) { }
        if (windows != null && !windows.isEmpty()) {
            for (AccessibilityWindowInfo w : windows) {
                // Skip our own floating toolbar.
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo root = null;
                try { root = w.getRoot(); } catch (Exception ignored) { }
                if (root == null) continue;
                if (ownPackage.contentEquals(nz(root.getPackageName())) && w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                if (topPkg == null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    topPkg = str(root.getPackageName());
                }
                j.beginObj();
                j.put("id", w.getId());
                j.put("type", windowType(w.getType()));
                j.put("layer", w.getLayer());
                j.put("active", w.isActive());
                j.put("focused", w.isFocused());
                if (Build.VERSION.SDK_INT >= 24) j.opt("title", w.getTitle());
                if (Build.VERSION.SDK_INT >= 26) j.put("pip", w.isInPictureInPictureMode());
                if (Build.VERSION.SDK_INT >= 30) j.put("display", w.getDisplayId());
                w.getBoundsInScreen(r);
                j.key("b"); bounds(r);
                j.key("root");
                walk(root, "/hierarchy", rootClassCount, 0, 0);
                j.endObj();
            }
        } else {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root != null) {
                topPkg = str(root.getPackageName());
                j.beginObj();
                j.put("id", root.getWindowId());
                j.put("type", "application");
                j.put("layer", 0);
                j.put("active", true);
                j.put("focused", true);
                root.getBoundsInScreen(r);
                j.key("b"); bounds(r);
                j.key("root");
                walk(root, "/hierarchy", rootClassCount, 0, 0);
                j.endObj();
            }
        }
        j.endArr();
        return topPkg;
    }

    private void walk(AccessibilityNodeInfo n, String parentPath,
                      HashMap<String, Integer> siblingClassCount, int siblingIndex, int depth) {
        int idx = nodes.size();
        nodes.add(n);

        String cls = xmlName(str(n.getClassName()));
        Integer c = siblingClassCount.get(cls);
        int nth = c == null ? 1 : c + 1;
        siblingClassCount.put(cls, nth);
        String xpath = parentPath + "/" + cls + "[" + nth + "]";

        j.beginObj();
        j.put("i", idx);
        j.put("index", siblingIndex);
        j.put("depth", depth);
        j.put("cls", str(n.getClassName()));
        j.opt("pkg", n.getPackageName());
        j.opt("text", n.getText());
        j.opt("desc", n.getContentDescription());
        j.opt("rid", n.getViewIdResourceName());
        j.put("xpath", xpath);
        n.getBoundsInScreen(r);
        j.key("b"); bounds(r);
        n.getBoundsInParent(r);
        j.key("bp"); bounds(r);
        j.put("win", n.getWindowId());

        // Boolean state: only "true" values are written; the client defaults the rest to false.
        flag("checkable", n.isCheckable());
        flag("checked", n.isChecked());
        flag("clickable", n.isClickable());
        flag("longClickable", n.isLongClickable());
        flag("contextClickable", n.isContextClickable());
        flag("enabled", n.isEnabled());
        flag("focusable", n.isFocusable());
        flag("focused", n.isFocused());
        flag("a11yFocused", n.isAccessibilityFocused());
        flag("selected", n.isSelected());
        flag("scrollable", n.isScrollable());
        flag("password", n.isPassword());
        flag("editable", n.isEditable());
        flag("multiLine", n.isMultiLine());
        flag("dismissable", n.isDismissable());
        flag("visible", n.isVisibleToUser());
        flag("contentInvalid", n.isContentInvalid());
        flag("canOpenPopup", n.canOpenPopup());
        if (Build.VERSION.SDK_INT >= 24) {
            flag("important", n.isImportantForAccessibility());
            j.put("drawingOrder", n.getDrawingOrder());
        }
        if (Build.VERSION.SDK_INT >= 26) {
            flag("showingHint", n.isShowingHintText());
            j.opt("hint", n.getHintText());
        }
        if (Build.VERSION.SDK_INT >= 28) {
            flag("heading", n.isHeading());
            flag("screenReaderFocusable", n.isScreenReaderFocusable());
            j.opt("tooltip", n.getTooltipText());
            j.opt("paneTitle", n.getPaneTitle());
        }
        if (Build.VERSION.SDK_INT >= 29) {
            flag("textEntryKey", n.isTextEntryKey());
        }
        if (Build.VERSION.SDK_INT >= 30) {
            j.opt("stateDesc", n.getStateDescription());
        }
        if (Build.VERSION.SDK_INT >= 33) {
            j.opt("uniqueId", n.getUniqueId());
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flag("a11yDataSensitive", n.isAccessibilityDataSensitive());
            j.opt("containerTitle", n.getContainerTitle());
        }
        j.opt("error", n.getError());
        if (n.getInputType() != 0) j.put("inputType", n.getInputType());
        if (n.getMaxTextLength() > 0) j.put("maxTextLength", n.getMaxTextLength());
        if (n.getTextSelectionStart() >= 0) {
            j.put("selStart", n.getTextSelectionStart());
            j.put("selEnd", n.getTextSelectionEnd());
        }
        if (n.getLiveRegion() != 0) j.put("liveRegion", n.getLiveRegion());
        if (n.getMovementGranularities() != 0) j.put("granularities", n.getMovementGranularities());

        AccessibilityNodeInfo.CollectionInfo ci = n.getCollectionInfo();
        if (ci != null) {
            j.key("collection").beginObj()
                    .put("rows", ci.getRowCount())
                    .put("cols", ci.getColumnCount())
                    .put("hierarchical", ci.isHierarchical())
                    .put("selectionMode", ci.getSelectionMode())
                    .endObj();
        }
        AccessibilityNodeInfo.CollectionItemInfo cii = n.getCollectionItemInfo();
        if (cii != null) {
            j.key("collectionItem").beginObj()
                    .put("row", cii.getRowIndex()).put("rowSpan", cii.getRowSpan())
                    .put("col", cii.getColumnIndex()).put("colSpan", cii.getColumnSpan())
                    .put("selected", cii.isSelected())
                    .endObj();
        }
        AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
        if (ri != null) {
            j.key("range").beginObj()
                    .put("type", ri.getType()).put("min", ri.getMin())
                    .put("max", ri.getMax()).put("current", ri.getCurrent())
                    .endObj();
        }

        AccessibilityNodeInfo labeledBy = n.getLabeledBy();
        if (labeledBy != null) {
            j.opt("labeledBy", labeledBy.getViewIdResourceName());
        }

        Bundle extras = n.getExtras();
        if (extras != null && !extras.isEmpty()) {
            j.key("extras").beginArr();
            for (String k : extras.keySet()) j.val(k);
            j.endArr();
        }

        List<AccessibilityAction> actions = n.getActionList();
        if (actions != null && !actions.isEmpty()) {
            j.key("actions").beginArr();
            for (AccessibilityAction a : actions) {
                String name = ACTION_NAMES.get(a.getId());
                if (name == null) {
                    CharSequence label = a.getLabel();
                    name = label != null ? "custom:" + label : "0x" + Integer.toHexString(a.getId());
                }
                j.val(name);
            }
            j.endArr();
        }

        int count = n.getChildCount();
        j.put("childCount", count);
        if (count > 0 && depth < MAX_DEPTH) {
            j.key("ch").beginArr();
            HashMap<String, Integer> childClassCount = new HashMap<>();
            int written = 0;
            for (int k = 0; k < count; k++) {
                if (nodes.size() >= MAX_NODES) break;
                AccessibilityNodeInfo child = null;
                try { child = n.getChild(k); } catch (Exception ignored) { }
                if (child == null) continue;
                walk(child, xpath, childClassCount, written++, depth + 1);
            }
            j.endArr();
        }
        j.endObj();
    }

    private void flag(String k, boolean v) { if (v) j.put(k, true); }

    private void bounds(Rect b) {
        j.beginArr().val(b.left).val(b.top).val(b.right).val(b.bottom).endArr();
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString(); }
    private static CharSequence nz(CharSequence s) { return s == null ? "" : s; }

    /** Makes a class name usable as an XML element / XPath step (e.g. "Foo$Bar" -> "Foo_Bar"). */
    static String xmlName(String cls) {
        if (cls.isEmpty()) return "node";
        StringBuilder sb = null;
        for (int i = 0; i < cls.length(); i++) {
            char c = cls.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
            if (i == 0 && !(Character.isLetter(c) || c == '_')) ok = false;
            if (!ok) {
                if (sb == null) sb = new StringBuilder(cls.substring(0, i));
                sb.append('_');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? cls : sb.toString();
    }

    private static String windowType(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "inputMethod";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "a11yOverlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "splitDivider";
            default:
                if (Build.VERSION.SDK_INT >= 33 && t == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY) {
                    return "magnification";
                }
                return "type" + t;
        }
    }

    static void recycle(List<AccessibilityNodeInfo> list) {
        if (list == null || Build.VERSION.SDK_INT >= 33) return; // no-op on 33+
        for (AccessibilityNodeInfo n : list) {
            try { n.recycle(); } catch (Exception ignored) { }
        }
    }
}
