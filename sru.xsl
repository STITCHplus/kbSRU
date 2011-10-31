<?xml version='1.0' encoding='UTF-8'?>
<xsl:stylesheet version='1.0'
    xmlns:xsl='http://www.w3.org/1999/XSL/Transform'
    xmlns:srw="http://www.loc.gov/zing/srw/">

  <xsl:output media-type="xml" encoding="UTF-8"/> 

 
  <xsl:template match='/'>
    <xsl:element name="srw:searchRetrieveResponse">
        <xsl:element name="srw:numberOfRecords">
            <xsl:value-of select="response/result/@numFound"/>
        </xsl:element>
        <xsl:element name="srw:records">
                <xsl:apply-templates select="response/result/doc"/>
        </xsl:element>
    </xsl:element>
  </xsl:template>

    <xsl:template match="doc">
            <xsl:element name="srw:record">
                <xsl:apply-templates/>
            </xsl:element>
    </xsl:template>

    <xsl:template match="doc/*">
        <xsl:element name="{@name}">
            <xsl:value-of select="."/>
        </xsl:element>
    </xsl:template>

</xsl:stylesheet>
