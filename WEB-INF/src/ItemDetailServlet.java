import java.io.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.*;

public class ItemDetailServlet extends HttpServlet {
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
        HttpSession session = request.getSession();
        if (session.getAttribute("u_id") == null) { response.sendRedirect("login"); return; }
        int myUid = (Integer) session.getAttribute("u_id");
        String idStr = request.getParameter("id");

        if (request.getParameter("toggleLike") != null) handleLike(idStr, myUid);

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {

                AuctionHelper.processNotifications(out, session, conn);
                AuctionHelper.printHeader(out, session, "itemDetail");

                String sql = "SELECT i.*, u.name as seller_name, " +
                        "(SELECT COUNT(*) FROM BidItem WHERE item_id = i.id) as bid_count " +
                        "FROM Item i JOIN Users u ON i.seller_id = u.id WHERE i.id = ?";
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setInt(1, Integer.parseInt(idStr));
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    String name = rs.getString("name");
                    String desc = rs.getString("description");
                    int openPrice = rs.getInt("opening_price");
                    int sellerId = rs.getInt("seller_id");
                    String sellerName = rs.getString("seller_name");
                    Timestamp beginAt = rs.getTimestamp("begin_at");
                    Timestamp endAt = rs.getTimestamp("end_at");
                    int bidCount = rs.getInt("bid_count");
                    boolean isBought = rs.getBoolean("is_bought");

                    String sqlMax = "SELECT b.bid_price, b.bidder_id, u.name " +
                            "FROM BidItem b JOIN Users u ON b.bidder_id = u.id " +
                            "WHERE b.item_id = ? ORDER BY b.bid_price DESC LIMIT 1";
                    PreparedStatement psMax = conn.prepareStatement(sqlMax);
                    psMax.setInt(1, Integer.parseInt(idStr));
                    ResultSet rsMax = psMax.executeQuery();

                    int maxBid = 0;
                    int winnerId = 0;
                    String winnerName = "";
                    if(rsMax.next()) {
                        maxBid = rsMax.getInt("bid_price");
                        winnerId = rsMax.getInt("bidder_id");
                        winnerName = rsMax.getString("name");
                    }

                    int currentPrice = (maxBid > 0) ? maxBid : openPrice;
                    String priceLabel = (maxBid > 0) ? "現在価格 (最高入札額)" : "現在価格 (開始価格)";
                    String status = AuctionHelper.getItemStatus(beginAt, endAt, isBought, bidCount > 0);

                    String winnerDisplay = "-";
                    if ("落札済".equals(status)) {
                        if (winnerId == myUid) winnerDisplay = "あなた";
                        else winnerDisplay = "<a href='userDetail?uid=" + winnerId + "'>" + winnerName + "</a>";
                    }

                    PreparedStatement psLikeCount = conn.prepareStatement("SELECT COUNT(*) FROM Likes WHERE item_id = ?");
                    psLikeCount.setInt(1, Integer.parseInt(idStr));
                    ResultSet rsLikeCount = psLikeCount.executeQuery();
                    int totalLikes = rsLikeCount.next() ? rsLikeCount.getInt(1) : 0;
                    boolean isLiked = conn.prepareStatement("SELECT 1 FROM Likes WHERE user_id="+myUid+" AND item_id="+idStr).executeQuery().next();
                    boolean isOwner = (myUid == sellerId);

                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm");

                    out.println("<h2>" + name + "</h2>");
                    String err = (String)request.getAttribute("error");
                    if(err != null) out.println("<p style='color:red; font-weight:bold;'>" + err + "</p>");

                    out.println("<table border='1' cellpadding='5' style='border-collapse:collapse; width:80%;'>");
                    out.println("<tr><th width='30%'>商品説明</th><td>" + (desc==null?"":desc) + "</td></tr>");
                    out.println("<tr><th>オークション状況</th><td style='font-weight:bold;'>" + status + "</td></tr>");
                    out.println("<tr><th>" + priceLabel + "</th><td>" + currentPrice + "円</td></tr>");
                    out.println("<tr><th>落札者</th><td>" + winnerDisplay + "</td></tr>");

                    if (isOwner) {
                        // ステータス行削除
                        out.println("<tr><th>入札数</th><td>" + bidCount + "件</td></tr>");
                        out.println("<tr><th>いいね総数</th><td>" + totalLikes + "件</td></tr>");
                    } else {
                        out.println("<tr><th>出品者</th><td><a href='userDetail?uid=" + sellerId + "'>" + sellerName + "</a></td></tr>");
                    }
                    out.println("<tr><th>開始日時</th><td>" + sdf.format(beginAt) + "</td></tr>");
                    out.println("<tr><th>終了日時</th><td>" + sdf.format(endAt) + "</td></tr>");
                    out.println("</table>");

                    out.println("<div style='margin-top:15px; padding:10px; background:#f9f9f9; border:1px solid #ddd;'>");
                    if (isOwner) {
                        out.println("<span>※自身の商品には入札・いいねはできません。</span>");
                    } else {
                        out.println("<a href='itemDetail?id=" + idStr + "&toggleLike=1' style='text-decoration:none; font-size:18px; margin-right:20px;'>"
                                + (isLiked ? "★ いいね済" : "☆ いいねする") + "</a>");

                        if ("開催中".equals(status)) {
                            out.println("<h3>入札する</h3>");
                            out.println("<form action='itemDetail' method='POST' onkeydown='stopEnter(event)'>");
                            out.println("<input type='hidden' name='id' value='" + idStr + "'>");
                            out.println("<input type='hidden' name='minPrice' value='" + currentPrice + "'>");
                            out.println("入札額: <input type='number' name='bid_price' value='" + (currentPrice+1) + "'>円 ");
                            out.println("<button type='button' onclick='this.form.submit()'>入札</button>");
                            out.println("</form>");
                        } else {
                            out.println("<span style='color:red; font-weight:bold;'>現在入札できません（" + status + "）</span>");
                        }
                    }
                    out.println("</div>");

                    out.println("<h3>入札履歴</h3><ul>");
                    PreparedStatement psHist = conn.prepareStatement("SELECT b.*, u.name FROM BidItem b JOIN Users u ON b.bidder_id = u.id WHERE item_id = ? ORDER BY bid_price DESC");
                    psHist.setInt(1, Integer.parseInt(idStr));
                    ResultSet rsHist = psHist.executeQuery();
                    while(rsHist.next()){
                        out.println("<li>" + rsHist.getInt("bid_price") + "円 - <a href='userDetail?uid=" + rsHist.getInt("bidder_id") + "'>" + rsHist.getString("name") + "</a></li>");
                    }
                    out.println("</ul>");
                }
            }
        } catch (Exception e) { e.printStackTrace(out); }
        AuctionHelper.printFooter(out);
    }
    // doPost 省略
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession();
        int myUid = (Integer) session.getAttribute("u_id");
        int itemId = Integer.parseInt(request.getParameter("id"));
        int bidPrice = Integer.parseInt(request.getParameter("bid_price"));
        int minPrice = Integer.parseInt(request.getParameter("minPrice"));
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                PreparedStatement psCheck = conn.prepareStatement("SELECT begin_at, end_at, seller_id FROM Item WHERE id = ?");
                psCheck.setInt(1, itemId);
                ResultSet rsCheck = psCheck.executeQuery();
                if(rsCheck.next()) {
                    long now = System.currentTimeMillis();
                    if (rsCheck.getInt("seller_id") == myUid) {
                        request.setAttribute("error", "自身の商品には入札できません"); doGet(request, response); return;
                    }
                    if (now < rsCheck.getTimestamp("begin_at").getTime()) {
                        request.setAttribute("error", "開催前です"); doGet(request, response); return;
                    }
                    if (now > rsCheck.getTimestamp("end_at").getTime()) {
                        request.setAttribute("error", "終了しています"); doGet(request, response); return;
                    }
                }
                if (bidPrice <= minPrice) {
                    request.setAttribute("error", "現在の価格より高い金額を指定してください"); doGet(request, response); return;
                }
                PreparedStatement ps = conn.prepareStatement("INSERT INTO BidItem (bidder_id, item_id, bid_price, is_success) VALUES (?, ?, ?, false)");
                ps.setInt(1, myUid); ps.setInt(2, itemId); ps.setInt(3, bidPrice);
                ps.executeUpdate();
                response.sendRedirect("itemDetail?id=" + itemId);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
    private void handleLike(String itemId, int uid) { /* 省略 */ }
}