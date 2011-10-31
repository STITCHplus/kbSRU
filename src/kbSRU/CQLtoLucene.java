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

import java.io.*;
import java.io.File;
import java.util.*;

import org.z3950.zing.cql.*;
import org.apache.log4j.Logger;

import javax.servlet.*;

public class CQLtoLucene {

    private static Properties config;
    private static Logger log;
    private static Logger logger_g;

    String cqlquery,lucenequery;

    public static String translate(String query, Logger logger, Properties c)
    throws IOException, ServletException {
        logger_g = logger;
        log = logger;
        config = c;

        boolean done = false; 

        CQLParser parser = new CQLParser();
        CQLNode root;

        try {
            query=query.trim();

            /*
            
            if (query.startsWith("\"") && (query.endsWith("\""))) {
                query=query.substring(1,query.length()-1);
            }

            */
            query=query.replaceAll(":", "\\\\:");
            query=query.replaceAll("\\?", "\\\\?");


            log.error("QUERY" + query+ " aaaaaaaaaaa");

            if (query.indexOf(">") >1 ) {

                while (query.indexOf(">") > 1) {
                    String q1=query.substring(0,query.indexOf(">"));
                    String q2=query.substring(query.indexOf(">")+1, query.length());
                    String q3=q2.substring(q2.indexOf(")")+1, q2.length());
                    query=q1+":["+q2.substring(0,q2.indexOf(")"))+" TO *])"+q3;
                }
                done=true;
            }


            if (query.indexOf("<") >1 ) {

                while (query.indexOf("<") > 1) {
                    String q1=query.substring(0,query.indexOf("<"));
                    String q2=query.substring(query.indexOf("<")+1, query.length());
                    String q3=q2.substring(q2.indexOf(")")+1, q2.length());
                    query=q1+":[* TO "+q2.substring(0,q2.indexOf(")"))+"])"+q3;
                }
                done=true;
            }
        
            if (!done) { 
                root = parser.parse(query);
                String result = ParseNode(root);

            log.debug("\nCQLtoLucene : SRU-Query : (" +result+")\n");
            return(result);
            } else {
            return(query);

            }

        } catch(CQLParseException e) {
            try {
                    query=query.replaceAll(" ", " AND ");
                    root = parser.parse(query);
                    String result = ParseNode(root);
                    return(result);

                }  catch(CQLParseException p) {

                    log.error("\nCQLtoLucene : Fatal parsing error : "+p.getCause());
                    return(query);
            }
         }
    }

    private static String ParseBooleanNode(CQLBooleanNode node)
    throws IOException, ServletException 
    {
       if (CQLAndNode.class.isInstance(node)) {
            return(ParseAndNode((CQLAndNode)node));
       }
       if (CQLOrNode.class.isInstance(node)) {
            return(ParseOrNode((CQLOrNode)node));
       }
       if (CQLNotNode.class.isInstance(node)) {
            return(ParseNotNode((CQLNotNode)node));
       }
       return(null);
    }

    private static String ParsePrefixNode(CQLPrefixNode node) 
    throws IOException, ServletException 
    {
        return("ok");
    }

    private static String ParseTermNode(CQLTermNode node) 
    throws IOException, ServletException 
    {
        String prefix = "";
        String relation = "";

        prefix = node.getIndex();
   
        StringTokenizer tokenizer = new StringTokenizer(prefix, ".");
        
        while (tokenizer.hasMoreTokens()) {
            prefix=tokenizer.nextToken();
        } 

        if (prefix.equalsIgnoreCase("serverchoice")) {
        ////    prefix = config.getProperty("cqlserverchoice");
            prefix = "";
        } 
        if (prefix.equalsIgnoreCase("any")) {
            prefix = "";
        } 

        relation = node.getRelation().getBase().replaceAll("cql.", "");
        String term = node.getTerm();

        if (relation.equalsIgnoreCase("="))  {
            term=term.replaceAll("\\?", "\\\\?");
            term=term.replaceAll("\\*", "\\\\*");
            term=term.replaceAll("\\~", "\\\\~");
            term=term.replaceAll("'", "\\'");
            term=term.replaceAll(":", "\\\\:");
            term=term.replaceAll("\\[", "\\\\[");
            term=term.replaceAll("\\]", "\\\\]");
        }


        if (relation.equalsIgnoreCase("exact"))  {
            if ( term.indexOf("date") == -1 ) {
                prefix=prefix+"_str";
                //term="\""+term+"\"";
                logger_g.error("A0" + prefix + term + " " + relation);
            } else {
                logger_g.error("A1" + prefix + relation);
                if ( prefix.length() > 0 ) {
                    logger_g.error("A2" + prefix + relation);
                    prefix=prefix+"_str";
                }
                relation="=";
                term="\""+term+"\"";
            }

        }

        if ( term.indexOf(" ") != -1 ) {
            if (relation.equalsIgnoreCase("all"))  {
                String nterm = "";
                for (String str: term.split(" ")) {
                    nterm = nterm+ "\""+str+"\""+" AND "+prefix+":";
                }
                term = nterm.substring(0, nterm.lastIndexOf(" AND "));
            } 
            if (relation.equalsIgnoreCase("any")) {
                String nterm = "";
                for (String str: term.split(" ")) {
                    nterm = nterm+ "\""+str+"\""+" OR "+prefix+":";
                }
                term = nterm.substring(0, nterm.lastIndexOf(" OR ")) ;
            }
        }

        if ( prefix.length() > 0 ) {
            return((String)prefix+":\""+term+"\"");
            //return(term);
        } else {
            //return((String)"cql.all "+helpers.urlEncode(term));
            return((String)"\""+term+"\"");
        }
    }


    private static String ParseAndNode(CQLAndNode node)
    throws IOException, ServletException 
    {
        StringBuffer result = new StringBuffer ("");

        result.append("(("+ParseNode(node.left));
        result.append(") AND (");
        result.append(ParseNode(node.right)+"))");

        return(result.toString());
    }

    private static String ParseOrNode(CQLOrNode node)
    throws IOException, ServletException 
    {
        StringBuffer result = new StringBuffer ("");
        result.append("((" + ParseNode(node.left));
        result.append(") OR (");
        result.append(ParseNode(node.right)+"))");
        return(result.toString());
    }

    private static String ParseNotNode(CQLNotNode node)
    throws IOException, ServletException 
    {
        StringBuffer result = new StringBuffer ("");
        result.append("((" + ParseNode(node.left));
        result.append(") AND NOT (");
        result.append(ParseNode(node.right)+"))");
        return(result.toString());
    }

    private static String ParseNode(CQLNode node)
    throws IOException, ServletException 
    {
        if (CQLBooleanNode.class.isInstance(node)) {
            return(ParseBooleanNode((CQLBooleanNode)node));
            
        } else if (CQLPrefixNode.class.isInstance(node)) {

            return(ParsePrefixNode((CQLPrefixNode)node));
        } else if (CQLTermNode.class.isInstance(node)) {
            return(ParseTermNode((CQLTermNode)node));
        }
        return(null);
    }
}
