import java.io.*;
import java.sql.*;
import java.util.Calendar;
import java.util.Properties;
import javax.servlet.*;
import javax.servlet.http.*;

public class SellServlet extends HttpServlet {
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
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("u_id") == null) {
            response.sendRedirect("login");
            return;
        }
        AuctionHelper.setNoCache(response);

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                AuctionHelper.processNotifications(out, session, conn);
            }
        } catch (Exception e) {}

        AuctionHelper.printHeader(out, session, "sell");

        String name = (String)request.getAttribute("name"); if(name==null) name="";
        String description = (String)request.getAttribute("description"); if(description==null) description="";
        String price = (String)request.getAttribute("price"); if(price==null) price="";
        String error = (String)request.getAttribute("error");
        String problemField = (String)request.getAttribute("problemField");

        out.println("<h2>出品する</h2>");
        if (error != null) out.println("<p style='color:red; font-weight:bold;'>エラー: " + error + "</p>");

        out.println("<form action='sell' method='POST' onkeydown='stopEnter(event)'>");
        out.println("商品名: <input type='text' name='name' value='" + name + "' style='" + ("name".equals(problemField)?"background:pink":"") + "'><br>");
        out.println("説明: <textarea name='description' style='" + ("description".equals(problemField)?"background:pink":"") + "'>" + description + "</textarea><br>");
        out.println("開始価格: <input type='number' name='price' value='" + price + "' style='" + ("price".equals(problemField)?"background:pink":"") + "'><br>");

        Calendar now = Calendar.getInstance();
        int curYear = now.get(Calendar.YEAR);
        out.println("開始日時: "); printDateSelect(out, "b", curYear, request); out.println("<br>");
        out.println("終了日時: "); printDateSelect(out, "e", curYear, request); out.println("<br>");
        out.println("<button type='button' onclick='this.form.submit()'>出品</button>");
        out.println("</form>");
        AuctionHelper.printFooter(out);
    }

    private void printDateSelect(PrintWriter out, String prefix, int curYear, HttpServletRequest req) {
        String pYear = (String)req.getAttribute(prefix + "_year");
        String pMonth = (String)req.getAttribute(prefix + "_month");
        String pDay = (String)req.getAttribute(prefix + "_day");
        String pHour = (String)req.getAttribute(prefix + "_hour");
        String pMin = (String)req.getAttribute(prefix + "_min");

        out.println("<select name='"+prefix+"_year'>");
        for(int y=curYear; y<=curYear+1; y++) out.println("<option value='"+y+"' " + (String.valueOf(y).equals(pYear)?"selected":"") + ">"+y+"</option>");
        out.println("</select>年 <select name='"+prefix+"_month'>");
        for(int m=1; m<=12; m++) out.println("<option value='"+m+"' " + (String.valueOf(m).equals(pMonth)?"selected":"") + ">"+m+"</option>");
        out.println("</select>月 <select name='"+prefix+"_day'>");
        for(int d=1; d<=31; d++) out.println("<option value='"+d+"' " + (String.valueOf(d).equals(pDay)?"selected":"") + ">"+d+"</option>");
        out.println("</select>日 <select name='"+prefix+"_hour'>");
        for(int h=0; h<=23; h++) out.println("<option value='"+h+"' " + (String.valueOf(h).equals(pHour)?"selected":"") + ">"+h+"</option>");
        out.println("</select>時 <select name='"+prefix+"_min'>");
        for(int m=0; m<=59; m+=10) out.println("<option value='"+m+"' " + (String.valueOf(m).equals(pMin)?"selected":"") + ">"+m+"</option>");
        out.println("</select>分");
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("u_id") == null) {
            response.sendRedirect("login");
            return;
        }
        int uid = (Integer) session.getAttribute("u_id");

        request.setCharacterEncoding("UTF-8");
        String name = request.getParameter("name");
        String desc = request.getParameter("description");
        String priceStr = request.getParameter("price");

        String bYear = request.getParameter("b_year"), bMonth = request.getParameter("b_month"), bDay = request.getParameter("b_day"), bHour = request.getParameter("b_hour"), bMin = request.getParameter("b_min");
        String eYear = request.getParameter("e_year"), eMonth = request.getParameter("e_month"), eDay = request.getParameter("e_day"), eHour = request.getParameter("e_hour"), eMin = request.getParameter("e_min");

        if(isEmpty(name)) returnError(request, response, "商品名は必須です", "name");
        else if(isEmpty(desc)) returnError(request, response, "説明は必須です", "description");
        else if(isEmpty(priceStr)) returnError(request, response, "価格は必須です", "price");
        else {
            try {
                int price = Integer.parseInt(priceStr);
                String bStr = String.format("%04d-%02d-%02d %02d:%02d:00", Integer.parseInt(bYear), Integer.parseInt(bMonth), Integer.parseInt(bDay), Integer.parseInt(bHour), Integer.parseInt(bMin));
                Timestamp beginAt = Timestamp.valueOf(bStr);
                String eStr = String.format("%04d-%02d-%02d %02d:%02d:00", Integer.parseInt(eYear), Integer.parseInt(eMonth), Integer.parseInt(eDay), Integer.parseInt(eHour), Integer.parseInt(eMin));
                Timestamp endAt = Timestamp.valueOf(eStr);

                if (!endAt.after(beginAt)) {
                    returnError(request, response, "終了日時は開始日時より後である必要があります", "date");
                    return;
                }

                Class.forName("org.postgresql.Driver");
                try (Connection conn = DriverManager.getConnection("jdbc:postgresql://" + _hostname + ":5432/" + _dbname, _username, _password)) {
                    // 出品登録 (INSERT)
                    PreparedStatement ps = conn.prepareStatement("INSERT INTO Item (name, description, opening_price, seller_id, begin_at, end_at, is_bought) VALUES (?, ?, ?, ?, ?, ?, false)");
                    ps.setString(1, name); ps.setString(2, desc); ps.setInt(3, price);
                    ps.setInt(4, uid); ps.setTimestamp(5, beginAt); ps.setTimestamp(6, endAt);
                    ps.executeUpdate();
                    response.sendRedirect("itemList");
                }
            } catch (Exception e) {
                e.printStackTrace();
                returnError(request, response, "入力またはDBエラー: " + e.getMessage(), "all");
            }
        }
    }
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private void returnError(HttpServletRequest req, HttpServletResponse res, String msg, String field) throws ServletException, IOException {
        req.setAttribute("error", msg); req.setAttribute("problemField", field);
        req.setAttribute("name", req.getParameter("name")); req.setAttribute("description", req.getParameter("description")); req.setAttribute("price", req.getParameter("price"));
        String[] prefixes = {"b", "e"}; String[] suffixes = {"_year", "_month", "_day", "_hour", "_min"};
        for(String p : prefixes) for(String s : suffixes) req.setAttribute(p+s, req.getParameter(p+s));
        doGet(req, res);
    }
}