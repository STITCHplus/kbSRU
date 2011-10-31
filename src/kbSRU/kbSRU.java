/*
    Copyright (c) 2010-2012 KB, Koninklijke Bibliotheek.

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
import java.util.regex.*;

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
import org.apache.solr.client.solrj.impl.XMLResponseParser;

import org.apache.solr.client.solrj.response.SpellCheckResponse;                                                                                                                                                                  
import org.apache.solr.common.params.SpellingParams;                                                                                                                                                                              
import org.apache.solr.handler.component.SpellCheckComponent;                                                                                                                                                                     

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
    private String SRW_DIAG = "";

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

        log.debug("maximumRecords : " + this.config.getProperty("default_maximumRecords"));
        log.debug("srw_header_file: " + this.config.getProperty("srw_header_file"));
        log.debug("srw_diag: " + this.config.getProperty("srw_diag_file"));
        //log.debug("server_list:     " + this.config.getProperty("server_list"));

        try {
            FileReader fis = new FileReader(getServletContext().getRealPath("/")+this.config.getProperty("srw_header_file")); 
            BufferedReader in1 = new BufferedReader(fis); 
            fis = new FileReader(getServletContext().getRealPath("/")+this.config.getProperty("srw_footer_file")); 
            BufferedReader in2 = new BufferedReader(fis); 
            fis = new FileReader(getServletContext().getRealPath("/")+this.config.getProperty("srw_diag_file")); 
            BufferedReader in3 = new BufferedReader(fis); 
        try {
            String line = null;

            while (( line = in1.readLine()) != null){
                this.SRW_HEADER=this.SRW_HEADER+line;
            }
            while (( line = in2.readLine()) != null){
                this.SRW_FOOTER=this.SRW_FOOTER+line;
            }
            while (( line = in3.readLine()) != null){
                this.SRW_DIAG=this.SRW_DIAG+line;
            }
        } finally {
            in1.close();
            in2.close();
            in3.close();
        }


        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public StringBuffer construct_lucene_solr (StringBuffer result, SolrDocument d) {
        for (String field : (d.getFieldNames())) {
            if ( (! field.endsWith("_str")) && (! field.equalsIgnoreCase("fullrecord") )) {
                Iterator j = d.getFieldValues(field).iterator();
                while (j.hasNext()) {
                    result.append("<"+field+">");
                    result.append(helpers.xmlEncode((String)j.next().toString()));
                    field=field.split(" ")[0];
                    result.append("</"+field+">");
                }
            }
        }
        return(result);
    }

    public String symantic_query (String query) {
        String st = query.split("\\[")[1].split("\\]")[0];

        String res = "";
        try {
            res = helpers.getExpand("http://www.kbresearch.nl/tripple.cgi?query="+helpers.urlEncode(st), log);
        }
        catch (MalformedURLException e) {res=(e.getMessage()); }
        return(res);

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
    response.setContentType(XML_RESPONSE_HEADER);  // Talkback happens in XML form.
    response.setCharacterEncoding("UTF-8");        // Unicode++
    request.setCharacterEncoding("UTF-8");
    
    PrintWriter out = null;                         // The talkback buffer.

    // handle startrecord 
    Integer startRecord = 0;
    
    if (!(request.getParameter("startRecord") == null)) {
        try{
            startRecord = Integer.parseInt(request.getParameter("startRecord"))-1;
        } catch (NumberFormatException e) {
            startRecord = 0;
        }
    }

    // maximumrecords
    Integer maximumRecords = Integer.parseInt(this.config.getProperty("default_maximumRecords"));
    if (!(request.getParameter("maximumRecords") == null)) {
        maximumRecords = Integer.parseInt(request.getParameter("maximumRecords"));
    }

    // operation 
    String  operation = request.getParameter("operation");

    // x_collection
    String  x_collection = request.getParameter("x-collection");
    if (x_collection == null) x_collection=this.config.getProperty("default_x_collection");
    if (x_collection == null) operation = null;


    // sortkeys
    String sortKeys = request.getParameter("sortKeys");

    // sortorder
    String  sortOrder = request.getParameter("sortOrder");

    // recordschema 
    String  recordSchema = request.getParameter("recordSchema");
    if (recordSchema == null) recordSchema = "dc";

    if (  recordSchema.equalsIgnoreCase("dcx")) {
        recordSchema = "dcx";
    }

    if ( recordSchema.equalsIgnoreCase("solr")) {
        recordSchema = "solr";
    }


    // query request 
    String  query = request.getParameter("query");
    String  q = request.getParameter("q");

    // who is requestor ?
    String remote_ip = request.getHeader("X-FORWARDED-FOR");

    if (remote_ip == null) {
        remote_ip = request.getRemoteAddr().trim();
    } else {
        remote_ip = request.getHeader("X-FORWARDED-FOR");
    }

    // handle debug 
    Boolean debug = Boolean.parseBoolean(request.getParameter("debug"));
    if (!debug) {
        out = new PrintWriter( new OutputStreamWriter(response.getOutputStream(), "UTF8"), true);
    }

    // handle query
    if (( query == null ) && ( q!=null )) {
        query=q;
    } else {
        if (( query != null ) && ( q == null)) {
            q=query;
        } else {
            operation=null;
        }
    }

    // handle operation
    if ( operation == null ) {
        if ( query != null) {
            operation = "searchRetrieve";
        } else {
            operation = "explain";
        }
    }

    //  searchRetrieve 
    if (operation.equalsIgnoreCase("searchRetrieve")) {
        if ( query == null) {
            operation="explain";
            log.debug(operation + ":" + query); 
        }
    }

    // start talking back.
    String [] sq = {""};
    String solrquery = "";


    // facet

    String facet = null;
    List<FacetField> fct = null;

    if ( request.getParameter("facet") != null ) {
        facet=request.getParameter("facet");
        log.debug("facet : " + facet);
    }


    if ( operation == null ) {
        operation = "searchretrieve";
    } else {  // explain response
        if (operation.equalsIgnoreCase("explain")) {
            log.debug("operation = explain");
            out.write("<srw:explainResponse xmlns:srw=\"http://www.loc.gov/zing/srw/\">");
            out.write("</srw:explainResponse>");
        } else {  // DEBUG routine
            operation="searchretrieve";

            String triplequery = null;

            if (query.matches(".*?\\[.+?\\].*?")) { // New symantic syntax
                triplequery=symantic_query(query);
                query = query.split("\\[")[0]+" "+triplequery;
                log.fatal(triplequery);

                solrquery=CQLtoLucene.translate(query, log, config);
            } else {
                solrquery = CQLtoLucene.translate(query, log, config);
            }
            log.debug(solrquery);

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
                out.write("<tr><td>SOLR_URL</td><td> <a href='"+ this.config.getProperty("collection."+x_collection.toLowerCase()+".solr_baseurl")  + "/?q="+solrquery + "'>"+ this.config.getProperty("collection."+x_collection.toLowerCase()+".solr_baseurl")  + "/select/?q="  + solrquery +"</a><br>"+this.config.getProperty("solr_url")  + solrquery  +"</td></tr>");
                out.write("<b>SOLR_QUERY</b> : <BR> <iframe width=900 height=400 src='"+ this.config.getProperty("collection."+x_collection.toLowerCase()+".solr_baseurl") + "/../?q="+solrquery + "'></iframe><BR>");
                out.write("<b>SRU_QUERY</b> : <BR> <a href="+this.config.getProperty("baseurl")+"?q="+query+"'>"+this.config.getProperty("baseurl")+"?q="+query+"</a><br><iframe width=901 height=400 src='http://www.kbresearch.nl/kbSRU/?q="+query+"'></iframe><BR>");
                out.write("<br><b>JSRU_QUERY</b> : <BR><a href='http://jsru.kb.nl/sru/?query="+query+"&x-collection="+x_collection+"'>http://jsru.kb.nl/sru/?query="+query+"&x-collection=GGC</a><br><iframe width=900 height=400 src='http://jsru.kb.nl/sru/?query="+query+"&x-collection=GGC'></iframe>");

            } else {   // XML SearchRetrieve response
                String  url = this.config.getProperty("collection."+x_collection.toLowerCase()+".solr_baseurl");
                String buffer="";
                CommonsHttpSolrServer server = null;
                server = new CommonsHttpSolrServer( url );
                log.fatal("URSING " + url);
                server.setParser(new XMLResponseParser());
                int numfound=0;
                try {
                    SolrQuery do_query = new SolrQuery();
                    do_query.setQuery(solrquery);
                    do_query.setRows(maximumRecords);
                    do_query.setStart(startRecord);


                    if (( sortKeys != null ) && (sortKeys.length() > 1) ) {
                        if (sortOrder != null ) {
                            if (sortOrder.equals("asc")) {
                                do_query.setSortField(sortKeys, SolrQuery.ORDER.asc);
                            }
                            if (sortOrder.equals("desc")) {
                                do_query.setSortField(sortKeys, SolrQuery.ORDER.desc);
                            }
                        } else {
                            for (String str: sortKeys.trim().split(",") ) {
                                str=str.trim();
                                if (str.length() > 1) {
                                    if ( str.equals("date")) {
                                        do_query.setSortField("date_date", SolrQuery.ORDER.desc);
                                        log.debug("SORTORDERDEBUG | DATE! " +str+" | ");
                                        break;
                                    } else {
                                        do_query.setSortField(str+"_str", SolrQuery.ORDER.asc);
                                        log.debug("SORTORDERDEBUG | " +str+" | ");
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    


                    if ( facet != null ) { 
                        if ( facet.indexOf(",") >1 ) { 
                            for (String str: facet.split(",")) { 
                                    if (str.indexOf("date") >1) {
                                        do_query.addFacetField(str); 
                                    } else {
                                        do_query.addFacetField(str); 
                                    }
                                    //do_query.setParam("facet.method", "enum");
                                }
                        //q.setFacetSort(false); 
                        } else {
                            do_query.addFacetField(facet); 
                        }
                        do_query.setFacet(true); 
                        do_query.setFacetMinCount(1); 
                        do_query.setFacetLimit(-1); 
                    }




    
                                
                    log.fatal(solrquery);

                    QueryResponse rsp = null;
                    boolean do_err = false;
                    boolean do_sugg= false;
                    SolrDocumentList sdl = null;
                    String diag = "";
                    StringBuffer suggest = new StringBuffer ("");

                    String content = "1";

                    SolrQuery spellq = do_query;
                    try {
                        rsp = server.query( do_query );
                    } catch (SolrServerException e) {  
                        String header = this.SRW_HEADER.replaceAll("\\$numberOfRecords",  "0");
                        out.write(header);
                        diag = this.SRW_DIAG.replaceAll("\\$error", e.getMessage());
                        do_err = true;
                        rsp=null;
                    }

                    log.fatal("query done.."); 
                     if (!(do_err)) { // XML dc response

                        SolrDocumentList docs = rsp.getResults();
                        numfound=(int)docs.getNumFound();
                        int count=startRecord;
                        String header = this.SRW_HEADER.replaceAll("\\$numberOfRecords",  Integer.toString(numfound));
                        out.write(header);
                        out.write("<srw:records>");

                        Iterator<SolrDocument> iter = rsp.getResults().iterator();

                        while (iter.hasNext()) {
                            count+=1;
                            if (recordSchema.equalsIgnoreCase("dc")) {
                                SolrDocument resultDoc = iter.next();
                                content = (String) resultDoc.getFieldValue("id");
                                out.write("<srw:record>");
                                out.write("<srw:recordPacking>xml</srw:recordPacking>");
                                out.write("<srw:recordSchema>info:srw/schema/1/dc-v1.1</srw:recordSchema>");
                                out.write("<srw:recordData xmlns:srw_dc=\"info:srw/schema/1/dc-v1.1\" xmlns:mods=\"http://www.loc.gov/mods\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:dcx=\"http://krait.kb.nl/coop/tel/handbook/telterms.html\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:marcrel=\"http://www.loc.gov/loc.terms/relators/OTH\" xmlns:facets=\"info:srw/extension/4/facets\" >");
                                StringBuffer result = new StringBuffer ("");

                                construct_lucene_dc(result, resultDoc);

                                out.write(result.toString());
                                out.write("</srw:recordData>");
                                out.write("<srw:recordPosition>"+ Integer.toString(count)+"</srw:recordPosition>");
                                out.write("</srw:record>");
                            } 

                            if (recordSchema.equalsIgnoreCase("solr")) {
                                SolrDocument resultDoc = iter.next();
                                content = (String) resultDoc.getFieldValue("id");
                                out.write("<srw:record>");
                                out.write("<srw:recordPacking>xml</srw:recordPacking>");
                                out.write("<srw:recordSchema>info:srw/schema/1/solr</srw:recordSchema>");
                                out.write("<srw:recordData xmlns:expand=\"http://www.kbresearch.nl/expand\">");
                                StringBuffer result = new StringBuffer ("");
                                construct_lucene_solr(result, resultDoc);
                                out.write(result.toString());

                                out.write("</srw:recordData>");
                                out.write("<srw:recordPosition>"+ Integer.toString(count)+"</srw:recordPosition>");
                                out.write("</srw:record>");
                            }

                            if (recordSchema.equalsIgnoreCase("dcx")) {  // XML dcx response
                                out.write("<srw:record>");
                                out.write("<srw:recordPacking>xml</srw:recordPacking>");
                                out.write("<srw:recordSchema>info:srw/schema/1/dc-v1.1</srw:recordSchema>");
                                out.write("<srw:recordData><srw_dc:dc xmlns:srw_dc=\"info:srw/schema/1/dc-v1.1\" xmlns:mods=\"http://www.loc.gov/mods\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:dcx=\"http://krait.kb.nl/coop/tel/handbook/telterms.html\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:marcrel=\"http://www.loc.gov/marc.relators/\" xmlns:expand=\"http://www.kbresearch.nl/expand\" xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\" xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" >");
                                SolrDocument resultDoc = iter.next();
                                content = (String) resultDoc.getFieldValue("id");

                                String dcx_data = helpers.getOAIdcx("http://services.kb.nl/mdo/oai?verb=GetRecord&identifier="+content, log);
                                if (x_collection.equalsIgnoreCase("ggc-thes")) {
                                    dcx_data = helpers.getOAIdcx("http://serviceso.kb.nl/mdo/oai?verb=GetRecord&identifier="+content, log);
                                }

                                if (!(dcx_data.length() == 0)) {
                                    out.write(dcx_data);
                                } else {
                                    // Should not do this!!

                                    out.write("<srw:record>");
                                    out.write("<srw:recordPacking>xml</srw:recordPacking>");
                                    out.write("<srw:recordSchema>info:srw/schema/1/dc-v1.1</srw:recordSchema>");
                                    out.write("<srw:recordData xmlns:srw_dc=\"info:srw/schema/1/dc-v1.1\" xmlns:mods=\"http://www.loc.gov/mods\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:dcx=\"http://krait.kb.nl/coop/tel/handbook/telterms.html\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:marcrel=\"http://www.loc.gov/loc.terms/relators/OTH\" >");
                                    StringBuffer result = new StringBuffer ("");

                                    construct_lucene_dc(result, resultDoc);

                                    out.write(result.toString());
                                    out.write("</srw:recordData>");
                                    out.write("<srw:recordPosition>"+ Integer.toString(count)+"</srw:recordPosition>");
                                    out.write("</srw:record>");

                                }


                                out.write("</srw_dc:dc>");

                               
                                StringBuffer expand_data;
                                boolean expand = false;

                                if (content.startsWith("GGC-THES:AC:")) {
                                    String tmp_content = "";
                                    tmp_content=content.replaceFirst("GGC-THES:AC:", "");
                                    log.fatal("calling get");
                                    expand_data = new StringBuffer(helpers.getExpand("http://www.kbresearch.nl/general/lod_new/get/"+tmp_content+"?format=rdf", log));
                                    log.fatal("get finini");


                                    if (expand_data.toString().length() > 4) {

                                        out.write("<srw_dc:expand xmlns:srw_dc=\"info:srw/schema/1/dc-v1.1\" xmlns:expand=\"http://www.kbresearch.nl/expand\" xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\" xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" >"); 
                                        out.write(expand_data.toString());
                                        expand = true;
                                    }
                                } else {
                                    expand_data = new StringBuffer(helpers.getExpand("http://www.kbresearch.nl/ANP.cgi?q="+content, log));
                                    if (expand_data.toString().length() > 0) {
                                        if (!expand) {
                                            out.write("<srw_dc:expand xmlns:srw_dc=\"info:srw/schema/1/dc-v1.1\" xmlns:expand=\"http://www.kbresearch.nl/expand\" xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\" xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" >"); 
                                            expand=true;
                                        }
                                        out.write(expand_data.toString());
                                    }
                                }
                                if (expand) {
                                    out.write("</srw_dc:expand>"); 
                                }

                                out.write("</srw:recordData>");
                                out.write("<srw:recordPosition>"+Integer.toString(count)+"</srw:recordPosition>");
                                out.write("</srw:record>");
                            }
                        }
                    }


                    if ((do_err) || (numfound == 0))  {
                        log.fatal("I haz suggestions");

                        try {
                            spellq.setParam("spellcheck", true);
                            spellq.setQueryType("/spell");
                            server = new CommonsHttpSolrServer( url );
                            rsp = server.query( spellq);
                            sdl = rsp.getResults();
                            SpellCheckResponse spell;
                            spell = rsp.getSpellCheckResponse();
                            List<SpellCheckResponse.Suggestion> suggestions = spell.getSuggestions();
                            if (suggestions.isEmpty() == false) {
                                    suggest.append("<srw:extraResponseData>"); 
                                    suggest.append("<suggestions>");

                                    for (SpellCheckResponse.Suggestion sugg : suggestions) {
                                        suggest.append("<suggestionfor>"+sugg.getToken()+"</suggestionfor>");
                                        for (String item: sugg.getSuggestions()) { 
                                            suggest.append("<suggestion>"+item+"</suggestion>"); 
                                        }
                                        suggest.append("</suggestions>");
                                        suggest.append("</srw:extraResponseData>");
                                        }
                                do_sugg = true;
                                }
                            } catch (Exception e) {  
                            rsp=null;
                            //log.fatal(e.toString());
                         };
                    };

 
                    if (! do_err ) {
                        if ( facet != null) {

                            try {
                                fct = rsp.getFacetFields();
                                out.write("<srw:facets>");

                                for (String str: facet.split(",") ) {
                                    out.write("<srw:facet>");
                                    out.write("<srw:facetType>");
                                    out.write(str);
                                    out.write("</srw:facetType>");

                                    for (FacetField f: fct) {
                                        log.debug(f.getName());
                                        //if (f.getName().equals(str+"_str") || (f.getName().equals(str+"_date")) ) {
                                            List<FacetField.Count> facetEnties = f.getValues();
                                            for (FacetField.Count fcount : facetEnties) {
                                                out.write("<srw:facetValue>");
                                                out.write("<srw:valueString>");
                                                out.write(helpers.xmlEncode(fcount.getName()));
                                                out.write("</srw:valueString>");
                                                out.write("<srw:count>");
                                                out.write(Double.toString(fcount.getCount()));
                                                out.write("</srw:count>");
                                                out.write("</srw:facetValue>");
                                         //   }
                                        }

                                    }
                                    out.write("</srw:facet>");
                                }
                                out.write("</srw:facets>");
                            startRecord +=1;
                            } catch (Exception e) {  }
                            
                            //log.fatal(e.toString()); }
                        }
                    } else {
                        out.write(diag);
                    }
                    out.write("</srw:records>");  // SearchRetrieve response footer
                    String footer = this.SRW_FOOTER.replaceAll("\\$query", helpers.xmlEncode(query));
                    footer = footer.replaceAll("\\$startRecord", (startRecord).toString());
                    footer = footer.replaceAll("\\$maximumRecords", maximumRecords.toString());
                    footer = footer.replaceAll("\\$recordSchema", recordSchema);
                    if (do_sugg) {
                        out.write(suggest.toString());
                    }
                    out.write(footer);
                } catch (MalformedURLException e) {out.write(e.getMessage()); } catch (IOException e) { out.write("TO ERR is Human"); } 
            }
        }
    }
    out.close();
  }
}
