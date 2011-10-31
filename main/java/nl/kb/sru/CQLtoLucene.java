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

    String cqlquery,lucenequery;

    public static String translate(String query, Logger logger, Properties c)
    throws IOException, ServletException {

        log = logger;
        config = c;

        CQLParser parser = new CQLParser();
        CQLNode root;

        try {
            root = parser.parse(query);
            String result = ParseNode(root);

            log.debug("\nCQLtoLucene : SRU-Query : (" +result+")\n");
            return(result);

        } catch(CQLParseException e) {
            log.error("\nCQLtoLucene : Fatal parsing error"+e.getCause());
            return(e.toString());
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
            prefix = config.getProperty("cqlserverchoice");
        } 
        if (prefix.equalsIgnoreCase("any")) {
            prefix = "";
        } 

        relation = node.getRelation().getBase().replaceAll("cql.", "");
        String term = node.getTerm();

        if (relation.equalsIgnoreCase("exact"))  {
            if ( term.indexOf("date") == -1 ) {
                prefix=prefix+"_str";
                term="\""+term+"\"";
            }
        }

        if (relation.equalsIgnoreCase("="))  {
            term=term.replaceAll("\\?", "\\\\?");
            term=term.replaceAll("\\*", "\\\\*");
            term=term.replaceAll("\\~", "\\\\~");
            term=term.replaceAll("'", "\\'");
            term=term.replaceAll("\\:", "\\\\:");
            term=term.replaceAll("\\[", "\\\\[");
            term=term.replaceAll("\\]", "\\\\]");
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
            //return((String)prefix+":"+helpers.urlEncode(term));
            return((String)prefix+":"+term);
        } else {
            //return((String)"cql.all "+helpers.urlEncode(term));
            return((String)"cql.all "+term);
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

        result.append("(" + ParseNode(node.left));
        result.append(" OR ");
        result.append(ParseNode(node.right)+")");

        return(result.toString());
    }

    private static String ParseNotNode(CQLNotNode node)
    throws IOException, ServletException 
    {

        StringBuffer result = new StringBuffer ("");
        result.append("(" + ParseNode(node.left));
        result.append(" AND NOT ");
        result.append(ParseNode(node.right)+")");
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
