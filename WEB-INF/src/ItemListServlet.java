import java.io.*;
import java.sql.*;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.*;

public class ItemListServlet extends HttpServlet {
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
        // セッションチェック & キャッシュ無効化
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("u_id") == null) {
            response.sendRedirect("login");
            return;
        }
        AuctionHelper.setNoCache(response);
        int myUid = (Integer) session.getAttribute("u_id");

        String search = request.getParameter("search");
        String filter = request.getParameter("filter");
        int page = 1;
        try { page = Integer.parseInt(request.getParameter("page")); } catch(Exception e){}
        if(page < 1) page = 1;

        if (request.getParameter("toggleLike") != null) handleLike(request.getParameter("toggleLike"), myUid);

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {

                AuctionHelper.processNotifications(out, session, conn);
                AuctionHelper.printHeader(out, session, "itemList");

                out.println("<form action='itemList' method='GET'>");
                out.println("検索: <input type='text' name='search' value='" + (search!=null?search:"") + "'>");
                out.println("<select name='filter'>");
                out.println("<option value='all'>全て</option>");
                out.println("<option value='liked' "+("liked".equals(filter)?"selected":"")+">Like済</option>");
                out.println("<option value='bidded' "+("bidded".equals(filter)?"selected":"")+">入札済</option>");
                out.println("<option value='active' "+("active".equals(filter)?"selected":"")+">開催中</option>");
                out.println("<option value='pre' "+("pre".equals(filter)?"selected":"")+">開催前</option>");
                out.println("<option value='post' "+("post".equals(filter)?"selected":"")+">終了済</option>");
                out.println("</select>");
                out.println("<button type='submit'>表示</button>");
                out.println("</form>");

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT i.*, u.name as seller_name, ");
                sql.append("(SELECT MAX(bid_price) FROM BidItem WHERE item_id = i.id) as max_bid, "); // 最高額
                sql.append("(SELECT COUNT(*) FROM Likes WHERE user_id = ? AND item_id = i.id) as is_liked, "); // いいね判定
                sql.append("(SELECT COUNT(*) FROM BidItem WHERE bidder_id = ? AND item_id = i.id) as is_bidded, "); // 入札判定
                sql.append("(SELECT bidder_id FROM BidItem WHERE item_id = i.id ORDER BY bid_price DESC LIMIT 1) as top_bidder "); // 勝者
                sql.append("FROM Item i JOIN Users u ON i.seller_id = u.id WHERE 1=1 "); // 出品者名結合

                sql.append("AND i.seller_id <> ? ");
                sql.append("AND i.seller_id NOT IN (SELECT to_user_id FROM Ban WHERE from_user_id = ?) ");
                sql.append("AND i.seller_id NOT IN (SELECT from_user_id FROM Ban WHERE to_user_id = ?) ");

                if (search != null && !search.isEmpty()) sql.append("AND i.name LIKE ? ");

                if ("liked".equals(filter)) sql.append("AND EXISTS (SELECT 1 FROM Likes WHERE user_id = ? AND item_id = i.id) ");
                else if ("bidded".equals(filter)) sql.append("AND EXISTS (SELECT 1 FROM BidItem WHERE bidder_id = ? AND item_id = i.id) ");
                else if ("active".equals(filter)) sql.append("AND i.begin_at <= NOW() AND i.end_at > NOW() ");
                else if ("pre".equals(filter)) sql.append("AND i.begin_at > NOW() ");
                else if ("post".equals(filter)) sql.append("AND i.end_at <= NOW() ");

                sql.append("ORDER BY CASE WHEN i.begin_at > NOW() THEN 0 ELSE 1 END DESC, ");
                sql.append("COALESCE((SELECT MAX(bid_price) FROM BidItem WHERE item_id = i.id), i.opening_price) DESC ");
                sql.append("LIMIT 20 OFFSET ?");

                PreparedStatement ps = conn.prepareStatement(sql.toString());
                int idx = 1;
                ps.setInt(idx++, myUid); ps.setInt(idx++, myUid);
                ps.setInt(idx++, myUid);
                ps.setInt(idx++, myUid); ps.setInt(idx++, myUid);
                if (search != null && !search.isEmpty()) ps.setString(idx++, "%" + search + "%");
                if ("liked".equals(filter) || "bidded".equals(filter)) ps.setInt(idx++, myUid);
                ps.setInt(idx++, (page - 1) * 20);

                ResultSet rs = ps.executeQuery();

                out.println("<table border='1' width='100%'>");
                out.println("<tr><th>商品名</th><th>状況</th><th>開始額</th><th>最高額</th><th>出品者</th><th>残り時間</th><th>いいね</th></tr>");

                boolean hit = false;
                while (rs.next()) {
                    hit = true;
                    int id = rs.getInt("id");
                    String name = rs.getString("name");
                    Timestamp start = rs.getTimestamp("begin_at");
                    Timestamp end = rs.getTimestamp("end_at");
                    int openP = rs.getInt("opening_price");
                    int maxP = rs.getInt("max_bid");
                    String seller = rs.getString("seller_name");
                    int sellerId = rs.getInt("seller_id");
                    boolean isLiked = rs.getInt("is_liked") > 0;
                    boolean isBought = rs.getBoolean("is_bought");
                    int topBidder = rs.getInt("top_bidder");

                    String status = AuctionHelper.getItemStatus(start, end, isBought, maxP > 0);
                    if ("落札済".equals(status)) {
                        if (topBidder == myUid) status += " <span style='color:blue; font-weight:bold;'>(あなた)</span>";
                    }

                    long now = System.currentTimeMillis();
                    String timeLeft = "";
                    if ("開催中".equals(status) || (now >= start.getTime() && now <= end.getTime())) {
                        long min = (end.getTime() - now) / 60000;
                        timeLeft = min + "分";
                        if (min <= 5) timeLeft = "<span style='color:red'>終了間近(" + min + "分)</span>";
                    }

                    out.println("<tr>");
                    out.println("<td><a href='itemDetail?id=" + id + "'>" + name + "</a></td>");
                    out.println("<td>" + status + "</td>");
                    out.println("<td>" + openP + "</td>");
                    out.println("<td>" + (maxP==0?"-":maxP) + "</td>");
                    out.println("<td><a href='userDetail?uid=" + sellerId + "'>" + seller + "</a></td>");
                    out.println("<td>" + timeLeft + "</td>");
                    String q = (search!=null?"&search="+search:"") + (filter!=null?"&filter="+filter:"") + "&page="+page;
                    out.println("<td><a href='itemList?toggleLike=" + id + q + "'>" + (isLiked ? "★解除" : "☆いいね") + "</a></td>");
                    out.println("</tr>");
                }
                out.println("</table>");
                if(!hit && search!=null) out.println("<p>ヒットなし</p>");

                out.println("<div style='margin-top:10px'>");
                for(int i=1; i<=5; i++) out.println("<a href='itemList?page=" + i + (search!=null?"&search="+search:"") + (filter!=null?"&filter="+filter:"") + "'>[" + i + "]</a> ");
                out.println("</div>");
            }
        } catch (Exception e) { e.printStackTrace(out); }
        AuctionHelper.printFooter(out);
    }
    private void handleLike(String itemId, int uid) {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                // Likeの切り替え処理 (INSERT/DELETE)
                PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM Likes WHERE user_id=? AND item_id=?");
                ps.setInt(1, uid); ps.setInt(2, Integer.parseInt(itemId));
                if (ps.executeQuery().next()) {
                    conn.prepareStatement("DELETE FROM Likes WHERE user_id="+uid+" AND item_id="+itemId).executeUpdate();
                } else {
                    conn.prepareStatement("INSERT INTO Likes (user_id, item_id) VALUES ("+uid+", "+itemId+")").executeUpdate();
                }
            }
        } catch(Exception e){}
    }
}