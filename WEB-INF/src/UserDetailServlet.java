import java.io.*;
import java.sql.*;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.*;

public class UserDetailServlet extends HttpServlet {
    private String _hostname, _dbname, _username, _password;
    public void init() throws ServletException {
        try {
            String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
            Properties prop = new Properties(); prop.load(new FileInputStream(iniFilePath));
            _hostname = prop.getProperty("hostname"); _dbname = prop.getProperty("dbname");
            _username = prop.getProperty("username"); _password = prop.getProperty("password");
        } catch (Exception e) { e.printStackTrace(); }
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // doGetは前回の修正のままでOK（変更なし）
        HttpSession session = request.getSession();
        int myUid = (Integer) session.getAttribute("u_id");
        int targetUid = Integer.parseInt(request.getParameter("uid"));

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {

                AuctionHelper.processNotifications(out, session, conn);
                AuctionHelper.printHeader(out, session, "userDetail");

                PreparedStatement psUser = conn.prepareStatement("SELECT name FROM Users WHERE id = ?");
                psUser.setInt(1, targetUid);
                ResultSet rsUser = psUser.executeQuery();
                String targetName = rsUser.next() ? rsUser.getString("name") : "不明";

                out.println("<h1>" + (myUid == targetUid ? "あなた" : targetName) + "</h1>");

                PreparedStatement psLikes = conn.prepareStatement("SELECT COUNT(*) FROM Likes l JOIN Item i ON l.item_id = i.id WHERE i.seller_id = ?");
                psLikes.setInt(1, targetUid);
                ResultSet rsLikes = psLikes.executeQuery();
                int likes = rsLikes.next() ? rsLikes.getInt(1) : 0;
                out.println("<p>獲得いいね総数: " + likes + "</p>");

                if (myUid != targetUid) {
                    out.println("<form action='userDetail' method='POST' onkeydown='stopEnter(event)'>");
                    out.println("<input type='hidden' name='action' value='ban'>");
                    out.println("<input type='hidden' name='target_uid' value='" + targetUid + "'>");
                    out.println("<button type='button' onclick='this.form.submit()' style='color:red'>このユーザーをBanする</button>");
                    out.println("</form>");
                }

                out.println("<h3>直近出品一覧</h3><ul>");
                PreparedStatement psSell = conn.prepareStatement("SELECT * FROM Item WHERE seller_id = ? ORDER BY begin_at DESC LIMIT 10");
                psSell.setInt(1, targetUid);
                ResultSet rsSell = psSell.executeQuery();
                while(rsSell.next()) {
                    int itemId = rsSell.getInt("id");
                    String itemName = rsSell.getString("name");
                    Timestamp beginAt = rsSell.getTimestamp("begin_at");
                    Timestamp endAt = rsSell.getTimestamp("end_at");
                    boolean isBought = rsSell.getBoolean("is_bought");
                    PreparedStatement psCheck = conn.prepareStatement("SELECT 1 FROM BidItem WHERE item_id = ? LIMIT 1");
                    psCheck.setInt(1, itemId);
                    boolean hasBid = psCheck.executeQuery().next();
                    String status = AuctionHelper.getItemStatus(beginAt, endAt, isBought, hasBid);

                    out.print("<li><a href='itemDetail?id=" + itemId + "'>" + itemName + "</a> (" + status + ")");

                    if (myUid == targetUid && "開催前".equals(status)) {
                        out.print(" <form action='userDetail' method='POST' style='display:inline' onsubmit='return confirm(\"削除してよろしいですか？\");'>");
                        out.print("<input type='hidden' name='action' value='delete_item'>");
                        out.print("<input type='hidden' name='item_id' value='" + itemId + "'>");
                        out.print("<input type='hidden' name='uid' value='" + myUid + "'>");
                        out.print("<button type='submit' style='font-size:small'>削除</button></form>");
                    }
                    out.println("</li>");
                }
                out.println("</ul>");

                out.println("<h3>直近入札一覧</h3><ul>");
                PreparedStatement psBid = conn.prepareStatement("SELECT DISTINCT ON (b.item_id) b.bid_price, i.name, i.id FROM BidItem b JOIN Item i ON b.item_id = i.id WHERE b.bidder_id = ? ORDER BY b.item_id, b.bid_price DESC LIMIT 10");
                psBid.setInt(1, targetUid);
                ResultSet rsBid = psBid.executeQuery();
                while(rsBid.next()) {
                    out.println("<li><a href='itemDetail?id=" + rsBid.getInt("id") + "'>" + rsBid.getString("name") + "</a> (" + rsBid.getInt("bid_price") + "円)</li>");
                }
                out.println("</ul>");

                out.println("<h3>過去の落札品一覧</h3><ul>");
                String sqlWon = "SELECT i.name, i.id, b.bid_price FROM Item i JOIN BidItem b ON i.id = b.item_id WHERE b.bidder_id = ? AND i.is_bought = TRUE AND b.is_success = TRUE";
                PreparedStatement psWon = conn.prepareStatement(sqlWon);
                psWon.setInt(1, targetUid);
                ResultSet rsWon = psWon.executeQuery();
                while(rsWon.next()){
                    out.println("<li><a href='itemDetail?id=" + rsWon.getInt("id") + "'>" + rsWon.getString("name") + "</a> (落札額: " + rsWon.getInt("bid_price") + "円)</li>");
                }
                out.println("</ul>");

                out.println("<h3>いいねした商品一覧</h3><ul>");
                String sqlLikedItems = "SELECT i.* FROM Likes l JOIN Item i ON l.item_id = i.id WHERE l.user_id = ? ORDER BY i.begin_at DESC";
                PreparedStatement psLiked = conn.prepareStatement(sqlLikedItems);
                psLiked.setInt(1, targetUid);
                ResultSet rsLiked = psLiked.executeQuery();
                boolean hasLiked = false;
                while(rsLiked.next()) {
                    hasLiked = true;
                    int itemId = rsLiked.getInt("id");
                    String itemName = rsLiked.getString("name");
                    Timestamp beginAt = rsLiked.getTimestamp("begin_at");
                    Timestamp endAt = rsLiked.getTimestamp("end_at");
                    boolean isBought = rsLiked.getBoolean("is_bought");
                    PreparedStatement psCheck = conn.prepareStatement("SELECT 1 FROM BidItem WHERE item_id = ? LIMIT 1");
                    psCheck.setInt(1, itemId);
                    boolean hasBid = psCheck.executeQuery().next();
                    String status = AuctionHelper.getItemStatus(beginAt, endAt, isBought, hasBid);
                    out.println("<li><a href='itemDetail?id=" + itemId + "'>" + itemName + "</a> (" + status + ")</li>");
                }
                if(!hasLiked) out.println("<li>なし</li>");
                out.println("</ul>");
            }
        } catch (Exception e) { e.printStackTrace(out); }
        AuctionHelper.printFooter(out);
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession();
        int myUid = (Integer) session.getAttribute("u_id");
        String action = request.getParameter("action");
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                // ▼▼▼ トランザクション処理の開始 ▼▼▼
                conn.setAutoCommit(false);
                try {
                    if ("ban".equals(action)) {
                        int targetUid = Integer.parseInt(request.getParameter("target_uid"));
                        // 1. Banテーブルへ追加
                        PreparedStatement psBan = conn.prepareStatement("INSERT INTO Ban (from_user_id, to_user_id) VALUES (?, ?)");
                        psBan.setInt(1, myUid); psBan.setInt(2, targetUid);
                        psBan.executeUpdate();
                        // 2. 関連する入札の削除 (双方向)
                        conn.prepareStatement("DELETE FROM BidItem WHERE bidder_id = "+myUid+" AND item_id IN (SELECT id FROM Item WHERE seller_id = "+targetUid+")").executeUpdate();
                        conn.prepareStatement("DELETE FROM BidItem WHERE bidder_id = "+targetUid+" AND item_id IN (SELECT id FROM Item WHERE seller_id = "+myUid+")").executeUpdate();

                        conn.commit(); // 成功したらコミット
                        response.sendRedirect("banList");
                    } else if ("delete_item".equals(action)) {
                        int itemId = Integer.parseInt(request.getParameter("item_id"));
                        PreparedStatement psCheck = conn.prepareStatement("SELECT * FROM Item WHERE id=? AND seller_id=? AND begin_at > NOW()");
                        psCheck.setInt(1, itemId); psCheck.setInt(2, myUid);
                        if (psCheck.executeQuery().next()) {
                            // 1. 子テーブル削除
                            conn.prepareStatement("DELETE FROM BidItem WHERE item_id=" + itemId).executeUpdate();
                            conn.prepareStatement("DELETE FROM Likes WHERE item_id=" + itemId).executeUpdate();
                            // 2. 親テーブル削除
                            conn.prepareStatement("DELETE FROM Item WHERE id=" + itemId).executeUpdate();
                        }
                        conn.commit(); // 成功したらコミット
                        response.sendRedirect("userDetail?uid=" + myUid);
                    }
                } catch (Exception e) {
                    conn.rollback(); // エラー時は全てなかったことにする
                    throw e;
                }
                // ▲▲▲ トランザクション処理の終了 ▲▲▲
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}