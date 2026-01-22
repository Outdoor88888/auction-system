import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import javax.servlet.http.*;

public class AuctionHelper {

    public static void printHeader(PrintWriter out, HttpSession session, String currentPage) {
        out.println("<html><head>");
        out.println("<meta charset='UTF-8'>");
        out.println("<script>");
        out.println("function stopEnter(e) { if(e.key === 'Enter') { e.preventDefault(); return false; } }");
        out.println("</script>");
        out.println("<style>");
        out.println("body { font-family: sans-serif; }");
        out.println(".menu-bar { background:#eee; padding:10px; margin-bottom:20px; border-bottom:1px solid #ccc; display:flex; justify-content:space-between; align-items:center; }");
        out.println(".menu-links a { text-decoration: none; margin-right: 15px; color: #00f; font-size:16px; }");
        out.println(".menu-links a.active { color: red; font-weight: bold; pointer-events: none; }");
        out.println(".menu-right { display:flex; align-items:center; gap:15px; }");
        out.println(".menu-right span { font-weight: bold; }");
        out.println(".logout-btn { color: #d00; text-decoration: none; border:1px solid #d00; padding:2px 8px; border-radius:4px; font-size:14px; }");
        out.println("</style>");
        out.println("</head><body>");

        if (session != null && session.getAttribute("u_id") != null) {
            out.println("<div class='menu-bar'><div class='menu-links'>");
            printLink(out, "itemList", "出品物一覧", currentPage);
            printLink(out, "sell", "出品する", currentPage);
            printLink(out, "userDetail?uid=" + session.getAttribute("u_id"), "ユーザー情報", currentPage);
            printLink(out, "banList", "Banリスト", currentPage);
            printLink(out, "submit?mode=edit", "登録情報変更", currentPage);
            out.println("</div>");

            out.println("<div class='menu-right'>");
            out.println("<span>ログイン中: " + session.getAttribute("name") + " 様</span>");
            out.println("<a href='login?action=logout' class='logout-btn'>ログアウト</a>");
            out.println("</div></div>");
        } else {
            out.println("<div class='menu-bar'><span>未ログイン</span></div>");
        }
    }

    private static void printLink(PrintWriter out, String url, String label, String currentPage) {
        boolean isActive = false;
        String target = currentPage.toLowerCase();
        String urlLower = url.toLowerCase();
        if (urlLower.startsWith(target)) isActive = true;
        if (target.equals("userdetail") && urlLower.contains("userdetail")) isActive = true;
        if (target.equals("submit") && urlLower.contains("submit")) isActive = true;
        if (target.equals("sell") && urlLower.contains("sell")) isActive = true;
        if (target.equals("banlist") && urlLower.contains("banlist")) isActive = true;
        out.println("<a href='" + url + "' class='" + (isActive ? "active" : "") + "'>" + label + "</a>");
    }

    public static void printFooter(PrintWriter out) {
        out.println("</body></html>");
    }

    private static void checkAndCloseAuctions(Connection conn) {
        try {
            String sql = "SELECT id FROM Item WHERE end_at <= NOW() AND is_bought = FALSE";
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            while(rs.next()) {
                int itemId = rs.getInt("id");
                String sqlBid = "SELECT id FROM BidItem WHERE item_id = ? ORDER BY bid_price DESC LIMIT 1";
                PreparedStatement psBid = conn.prepareStatement(sqlBid);
                psBid.setInt(1, itemId);
                ResultSet rsBid = psBid.executeQuery();
                if (rsBid.next()) {
                    int bidId = rsBid.getInt("id");
                    PreparedStatement psUpdateBid = conn.prepareStatement("UPDATE BidItem SET is_success = TRUE WHERE id = ?");
                    psUpdateBid.setInt(1, bidId);
                    psUpdateBid.executeUpdate();
                }
                PreparedStatement psUpdateItem = conn.prepareStatement("UPDATE Item SET is_bought = TRUE WHERE id = ?");
                psUpdateItem.setInt(1, itemId);
                psUpdateItem.executeUpdate();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @SuppressWarnings("unchecked")
    public static void processNotifications(PrintWriter out, HttpSession session, Connection conn) {
        if (session == null || session.getAttribute("u_id") == null) return;

        checkAndCloseAuctions(conn);

        int myUid = (Integer) session.getAttribute("u_id");
        long now = System.currentTimeMillis();
        Long lastCheck = (Long) session.getAttribute("last_check_time");
        if (lastCheck == null) lastCheck = now;

        List<String> messages = new ArrayList<>();

        try {
            // 1. オークション開始 (自身の出品)
            String sqlStart = "SELECT name FROM Item WHERE seller_id=? AND begin_at > ? AND begin_at <= ?";
            PreparedStatement psStart = conn.prepareStatement(sqlStart);
            psStart.setInt(1, myUid);
            psStart.setTimestamp(2, new Timestamp(lastCheck));
            psStart.setTimestamp(3, new Timestamp(now));
            ResultSet rsStart = psStart.executeQuery();
            while(rsStart.next()) messages.add("出品物「" + rsStart.getString("name") + "」のオークションが開始されました。");

            // 2. オークション終了 (自身の出品)
            String sqlEnd = "SELECT name FROM Item WHERE seller_id=? AND end_at > ? AND end_at <= ?";
            PreparedStatement psEnd = conn.prepareStatement(sqlEnd);
            psEnd.setInt(1, myUid);
            psEnd.setTimestamp(2, new Timestamp(lastCheck));
            psEnd.setTimestamp(3, new Timestamp(now));
            ResultSet rsEnd = psEnd.executeQuery();
            while(rsEnd.next()) messages.add("出品物「" + rsEnd.getString("name") + "」のオークションが終了しました。");

            // 3. 高値更新 (Outbid)
            Set<Integer> outbidItems = (Set<Integer>) session.getAttribute("outbid_items");
            if (outbidItems == null) outbidItems = new HashSet<>();
            String sqlOutbid = "SELECT i.id, i.name FROM BidItem b JOIN Item i ON b.item_id = i.id " +
                    "WHERE b.bidder_id = ? AND i.end_at > NOW() " +
                    "AND (SELECT MAX(bid_price) FROM BidItem WHERE item_id = i.id) > b.bid_price";
            PreparedStatement psOutbid = conn.prepareStatement(sqlOutbid);
            psOutbid.setInt(1, myUid);
            ResultSet rsOutbid = psOutbid.executeQuery();
            Set<Integer> currentOutbidIds = new HashSet<>();
            while(rsOutbid.next()) {
                int itemId = rsOutbid.getInt("id");
                currentOutbidIds.add(itemId);
                if (!outbidItems.contains(itemId)) {
                    messages.add("入札した商品「" + rsOutbid.getString("name") + "」の高値が更新されました。");
                }
            }
            session.setAttribute("outbid_items", currentOutbidIds);

            // 4. 落札 (Won) - 修正: 時間条件を追加して重複通知を防止
            Set<Integer> wonItems = (Set<Integer>) session.getAttribute("won_items");
            if (wonItems == null) wonItems = new HashSet<>();
            String sqlWon = "SELECT i.id, i.name FROM Item i JOIN BidItem b ON i.id = b.item_id " +
                    "WHERE b.bidder_id = ? AND i.is_bought = TRUE AND b.is_success = TRUE " +
                    "AND i.end_at > ? AND i.end_at <= ?";
            PreparedStatement psWon = conn.prepareStatement(sqlWon);
            psWon.setInt(1, myUid);
            psWon.setTimestamp(2, new Timestamp(lastCheck));
            psWon.setTimestamp(3, new Timestamp(now));
            ResultSet rsWon = psWon.executeQuery();
            Set<Integer> currentWonIds = new HashSet<>();
            while(rsWon.next()) {
                int itemId = rsWon.getInt("id");
                currentWonIds.add(itemId);
                // 時間で絞っているのでセットチェックは必須ではないが、同じセッション内での重複防止のため残す
                if (!wonItems.contains(itemId)) {
                    messages.add("おめでとうございます！商品「" + rsWon.getString("name") + "」を落札しました。");
                }
            }
            session.setAttribute("won_items", currentWonIds);

            // 5. いいねした商品のオークション開始
            String sqlLikeStart = "SELECT i.name FROM Likes l JOIN Item i ON l.item_id = i.id " +
                    "WHERE l.user_id=? AND i.begin_at > ? AND i.begin_at <= ?";
            PreparedStatement psLikeStart = conn.prepareStatement(sqlLikeStart);
            psLikeStart.setInt(1, myUid);
            psLikeStart.setTimestamp(2, new Timestamp(lastCheck));
            psLikeStart.setTimestamp(3, new Timestamp(now));
            ResultSet rsLikeStart = psLikeStart.executeQuery();
            while(rsLikeStart.next()) {
                messages.add("いいねした商品「" + rsLikeStart.getString("name") + "」のオークションが開始されました。");
            }

        } catch (Exception e) { e.printStackTrace(); }

        if (!messages.isEmpty()) {
            out.println("<script>");
            out.print("alert('【通知】\\n");
            for (String msg : messages) out.print("- " + msg + "\\n");
            out.println("');");
            out.println("</script>");
        }
        session.setAttribute("last_check_time", now);
    }

    public static String getItemStatus(Timestamp begin, Timestamp end, boolean isBought, boolean hasBid) {
        long now = System.currentTimeMillis();
        if (now < begin.getTime()) return "開催前";
        if (now <= end.getTime()) return "開催中";
        if (hasBid) return "落札済";
        return "取引不成立";
    }
}