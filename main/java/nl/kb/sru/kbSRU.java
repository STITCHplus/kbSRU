/*
    Copyright (c) 2010 KB, Koninklijke Bibliotheek.

    Maintainer : Willem Jan Faber
    Requestor : Theo van Veen

    This file is part of kbSRU.

    kbSRU is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    kbSRU is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with kbSRU. If not, see <http://www.gnu.org/licenses/>.

*/

package kbSRU;

import kbSRU.helpers.*;
import kbSRU.CQLtoLucene.*;

import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.net.*;

import javax.servlet.http.*;
import javax.servlet.*;

import org.apache.log4j.Logger;
import org.apache.log4j.BasicConfigurator;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;

import org.apache.solr.client.solrj.*;
import org.apache.solr.common.*;
import org.apache.solr.core.*;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.*;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrDocument;

import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.*;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.FacetField;

import java.io.*;

public class kbSRU extends HttpServlet {

    public Logger log = Logger.getLogger(kbSRU.class);

    private static Properties config = new Properties();

    final private static String XML_RESPONSE_HEADER   = "text/xml; charset=UTF-8";
    final private static String BASIC_RESPONSE_HEADER = "text/plain;";
    final private static String HTML_RESPONSE_HEADER  = "text/html; charset=UTF-8";

    private String SRW_HEADER = "";
    private String SRW_FOOTER = "";

    public void init() throws ServletException {

        final String CONFIG_PATH = getServletContext().getRealPath("/")+"config.ini";   
        final String APPLICATION_NAME = getServletContext().getServletContextName();

        BasicConfigurator.configure();
        
        FileInputStream config_file = null;
        File config = new File(CONFIG_PATH);
        
        if (config.canRead()) {
            log.debug("Parsing "+CONFIG_PATH);
            try {
                config_file = new FileInputStream(CONFIG_PATH);
            } catch (java.io.FileNotFoundException e) {
                throw new ServletException(e);
            }

            Properties config_prop = new Properties();

            try {
                config_prop.load(config_file);
            } catch (java.io.IOException e) {
                throw new ServletException(e);
            }
            this.config = config_prop;

            log.debug("Parsing finished");
        } else {
            log.fatal("Error, cannot read "+ CONFIG_PATH);
        }

        log.debug("Init fase done");
        log.debug("Default settings : ");
        log.debug("maximumRecords : " + this.config.getProperty("default_maximumRecords"));
        log.debug("srw_header_file: " + this.config.getProperty("srw_header_file"));
        log.debug("server_list:     " + this.config.getProperty("server_list"));



        try {
            FileReader fis = new FileReader(getServletContext().getRealPath("/")+this.config.getProperty("srw_header_file")); 
            BufferedReader in1 = new BufferedReader(fis); 
            fis = new FileReader(getServletContext().getRealPath("/")+this.config.getProperty("srw_footer_file")); 
            BufferedReader in2 = new BufferedReader(fis); 
        try {
            String line = null;

            while (( line = in1.readLine()) != null){
                this.SRW_HEADER=this.SRW_HEADER+line;
            }
            while (( line = in2.readLine()) != null){
                this.SRW_FOOTER=this.SRW_FOOTER+line;
            }
        } finally {
            in1.close();
            in2.close();
        }


        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public StringBuffer construct_lucene_dc(StringBuffer result, SolrDocument d) {
        for (String field : (d.getFieldNames())) {
            StringTokenizer dcfields = new StringTokenizer(this.config.getProperty("dcfields", ","));
            while (dcfields.hasMoreTokens()) {
                String dcfield = dcfields.nextToken().trim().replaceAll(",", "");
                if (field.equalsIgnoreCase(dcfield)) {
                    Iterator j = d.getFieldValues(field).iterator();
                    String rename = this.config.getProperty("solr."+field);
                    if ( rename != null ) {
                        field=rename;
                    } else {
                        field="dc:"+field;
                    }
                    while (j.hasNext()) {
                        result.append("<"+field+">");
                        result.append(helpers.xmlEncode((String)j.next()));
                        field=field.split(" ")[0];
                        
                        result.append("</"+field+">");
                    }
                }
            }
        }
        return(result);
    }


  public void doGet (HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
  {
    response.setContentType(XML_RESPONSE_HEADER);
    response.setCharacterEncoding("UTF-8");
    request.setCharacterEncoding("UTF-8");
 
    String  x_collection = request.getParameter("x-collection");
    String  operation = request.getParameter("operation");

    String  query = request.getParameter("query");
    String  q = request.getParameter("q");

    String remote_ip = request.getHeader("X-FORWARDED-FOR");

    if (remote_ip == null) {
        remote_ip = request.getRemoteAddr().trim();
    } else {
        remote_ip = request.getHeader("X-FORWARDED-FOR");
    }

    PrintWriter out = null;

    Boolean debug = Boolean.parseBoolean(request.getParameter("debug"));

    if (!debug) {
        out = new PrintWriter( new OutputStreamWriter(response.getOutputStream(), "UTF8"), true);
    }

    /// handle the query parameter
    if (( query == null ) && ( q!=null )) {
        query=q;
    } else {
        if (( query != null ) && ( q == null)) {
            q=query;
        } else {
            operation=null;
        }
    }

    /// handle the operation parameter.
    if ( operation == null ) {
        if ( query != null) {
            operation = "searchRetrieve";
        } else {
            operation = "explain";
        }
    }

    if (operation.equalsIgnoreCase("searchRetrieve")) {
        if ( query == null) {
            operation="explain";
            log.debug(operation + ":" + query); 
        }
    }

    // start talking back.
    if ( operation == null ) {
        operation = "searchretrieve";
    } else {
        if (operation.equalsIgnoreCase("explain")) {
            log.debug("operation = explain");

            out.write("<srw:explainResponse xmlns:srw=\"http://www.loc.gov/zing/srw/\">");
            out.write("</srw:explainResponse>");
        } else {
            operation="searchretrieve";
            String solrquery = CQLtoLucene.translate(query, log, config);
            if ( debug == true ) {  
                response.setContentType(HTML_RESPONSE_HEADER);
                out = new PrintWriter( new OutputStreamWriter(response.getOutputStream(), "UTF8"), true);
                out.write("<html><body>\n\n");
                out.write("'"+remote_ip+"'<br>\n");
                out.write("<form action='http://www.kbresearch.nl/kbSRU'>");
                out.write("<input type=text name=q value='"+ query + "' size=120>");
                out.write("<input type=hidden name=debug value=True>");
                out.write("<input type=submit>");
                out.write("<table border=1><tr><td>");
                out.write("q</td><td>" + query + "</td></tr><tr>");
                out.write("<td>query out</td><td>" + URLDecoder.decode(solrquery) + "</td></tr>");
                out.write("<tr><td>SOLR_URL</td><td> <a href='"+ this.config.getProperty("solr_url")  + solrquery + "'>"+ this.config.getProperty("solr_url")  + solrquery +"</a><br>"+this.config.getProperty("solr_url")  + solrquery  +"</td></tr>");
                out.write("<b>SOLR_QUERY</b> : <BR> <iframe width=900 height=400 src=\""+ this.config.getProperty("solr_url")  + solrquery + "\"></iframe><BR>");
                out.write("<b>SRU_QUERY</b> : <BR> <a href=http://www.kbresearch.nl/kbSRU/?q="+query+"'>http://www.kbresearch.nl/kbSRU/?q="+query+"</a><br><iframe width=901 height=400 src='http://www.kbresearch.nl/kbSRU/?q="+query+"'></iframe><BR>");
                out.write("<br><b>JSRU_QUERY</b> : <BR><a href='http://jsru.kb.nl/sru/?query="+query+"&x-collection=GGC'>http://jsru.kb.nl/sru/?query="+query+"&x-collection=GGC</a><br><iframe width=900 height=400 src='http://jsru.kb.nl/sru/?query="+query+"&x-collection=GGC'></iframe>");

            } else {   // SearchRetrieve
                try {
                String buffer="";
                String url = "http://127.0.0.1:8080/solr/ggc/";
                CommonsHttpSolrServer server = null;
                server = new CommonsHttpSolrServer( url );

                
                SolrQuery do_query = new SolrQuery();
                do_query.setQuery(solrquery);

                QueryResponse rsp = null;

                try {
                    rsp = server.query( do_query );
                } catch (SolrServerException e) {  
                    out.write("<error>bakvis</error>");
                }

                if (!(rsp== null)) {
                SolrDocumentList docs = rsp.getResults();

                int numfound=(int)docs.getNumFound();

                String header = this.SRW_HEADER.replaceAll("\\$numberOfRecords",  Integer.toString(numfound));

                out.write(header);
                out.write("<srw:records>");


                Iterator<SolrDocument> iter = rsp.getResults().iterator();
                while (iter.hasNext()) {
                    SolrDocument resultDoc = iter.next();
                    String content = (String) resultDoc.getFieldValue("id");
                    out.write("<srw:record>");
                    out.write("<srw:recordPacking>xml</srw:recordPacking>");
                    out.write("<srw:recordSchema>info:srw/schema/1/dc-v1.1</srw:recordSchema>");
                    out.write("<srw:recordData><srw_dc:dc xmlns:srw_dc=\"info:srw/schema/1/dc-v1.1\" xmlns:mods=\"http://www.loc.gov/mods\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:dcx=\"http://krait.kb.nl/coop/tel/handbook/telterms.html\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">");
                    StringBuffer result = new StringBuffer ("");
                    construct_lucene_dc(result, resultDoc);
                    out.write(result.toString());
                    
                    out.write("</srw_dc:dc>");
                    out.write("</srw:recordData>");
                    out.write("</srw:record>");
                    }
                }

                out.write("</srw:records>");

                String footer = this.SRW_FOOTER.replaceAll("\\$query", query);

                out.write(footer);

                } catch (MalformedURLException e) {} catch (IOException e) {}
            }
        }
    }
    out.close();
  }

}
