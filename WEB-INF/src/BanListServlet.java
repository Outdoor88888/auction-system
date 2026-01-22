import java.io.*;
import java.sql.*;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.*;

public class BanListServlet extends HttpServlet {
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
        int myUid = (Integer) session.getAttribute("u_id");

        String unbanId = request.getParameter("unban");
        if(unbanId != null) {
            try {
                Class.forName("org.postgresql.Driver");
                try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                    PreparedStatement ps = conn.prepareStatement("DELETE FROM Ban WHERE from_user_id=? AND to_user_id=?");
                    ps.setInt(1, myUid); ps.setInt(2, Integer.parseInt(unbanId));
                    ps.executeUpdate();
                }
            } catch(Exception e){}
            response.sendRedirect("banList");
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                AuctionHelper.processNotifications(out, session, conn);
                AuctionHelper.printHeader(out, session, "banList"); // 修正: 小文字開始

                out.println("<h2>Banリスト</h2><table border='1'>");
                PreparedStatement ps = conn.prepareStatement("SELECT b.to_user_id, u.name FROM Ban b JOIN Users u ON b.to_user_id = u.id WHERE b.from_user_id = ?");
                ps.setInt(1, myUid);
                ResultSet rs = ps.executeQuery();
                while(rs.next()){
                    out.println("<tr>");
                    out.println("<td>" + rs.getString("name") + "</td>");
                    out.println("<td><a href='banList?unban=" + rs.getInt("to_user_id") + "'>Ban解除</a></td></tr>");
                }
                out.println("</table>");
            }
        } catch (Exception e) { e.printStackTrace(out); }
        AuctionHelper.printFooter(out);
    }
}