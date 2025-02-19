/*
 * Copyright (c) 2004-2019, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.hisp.dhis.analytics.event.data;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hisp.dhis.DhisConvenienceTest.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.hisp.dhis.analytics.event.EventQueryParams;
import org.hisp.dhis.analytics.event.data.programIndicator.DefaultProgramIndicatorSubqueryBuilder;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.jdbc.StatementBuilder;
import org.hisp.dhis.jdbc.statementbuilder.PostgreSQLStatementBuilder;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramIndicator;
import org.hisp.dhis.program.ProgramIndicatorService;
import org.hisp.dhis.random.BeanRandomizer;
import org.hisp.dhis.relationship.RelationshipConstraint;
import org.hisp.dhis.relationship.RelationshipEntity;
import org.hisp.dhis.relationship.RelationshipType;
import org.hisp.dhis.system.grid.ListGrid;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;

/**
 * @author Luciano Fiandesio
 */
public class EnrollmentAnalyticsManagerTest
    extends
    EventAnalyticsTest {

    private JdbcEnrollmentAnalyticsManager subject;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SqlRowSet rowSet;

    @Mock
    private ProgramIndicatorService programIndicatorService;

    @Captor
    private ArgumentCaptor<String> sql;

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    private String DEFAULT_COLUMNS = "pi,tei,enrollmentdate,incidentdate,ST_AsGeoJSON(pigeometry),longitude,latitude,ouname,oucode";

    private final String TABLE_NAME = "analytics_enrollment";

    @Before
    public void setUp() {
        when( jdbcTemplate.queryForRowSet( anyString() ) ).thenReturn( this.rowSet );

        StatementBuilder statementBuilder = new PostgreSQLStatementBuilder();
        DefaultProgramIndicatorSubqueryBuilder programIndicatorSubqueryBuilder = new DefaultProgramIndicatorSubqueryBuilder(
            programIndicatorService );

        subject = new JdbcEnrollmentAnalyticsManager(jdbcTemplate, statementBuilder, programIndicatorService, programIndicatorSubqueryBuilder);
    }

    @Test
    public void verifyWithProgramAndStartEndDate() {
        EventQueryParams params = new EventQueryParams.Builder(createRequestParams())
                .withStartDate(getDate(2017, 1, 1)).withEndDate(getDate(2017, 12, 31)).build();

        subject.getEnrollments(params, new ListGrid(), 10000);

        verify(jdbcTemplate).queryForRowSet(sql.capture());

        String expected = "ax.\"monthly\",ax.\"ou\"  from " + getTable(programA.getUid())
                + " as ax where enrollmentdate >= '2017-01-01' and enrollmentdate <= '2017-12-31' and (uidlevel0 = 'ouabcdefghA' ) limit 10001";

        assertSql( sql.getValue(), expected );

    }

    @Test
    public void verifyWithProgramStageAndNumericDataElement() {
        verifyWithProgramStageAndNumericDataElement(ValueType.NUMBER);
    }

    @Test
    public void verifyWithProgramStageAndTextDataElement() {
        verifyWithProgramStageAndNumericDataElement(ValueType.TEXT);
    }

    private void verifyWithProgramStageAndNumericDataElement(ValueType valueType) {

        EventQueryParams params = createRequestParams(this.programStage, valueType);

        subject.getEnrollments(params, new ListGrid(), 100);

        verify(jdbcTemplate).queryForRowSet(sql.capture());

        String subSelect = "(select \"fWIAEtYVEGk\" from analytics_event_" + programA.getUid()
                + " where analytics_event_" + programA.getUid() + ".pi = ax.pi and \"fWIAEtYVEGk\" is not null and ps = '"
                + programStage.getUid() + "' order by executiondate desc limit 1 )";

        String expected = "ax.\"monthly\",ax.\"ou\"," + subSelect + "  from " + getTable(programA.getUid())
                + " as ax where ax.\"monthly\" in ('2000Q1') and (uidlevel0 = 'ouabcdefghA' ) " + "and ps = '"
                + programStage.getUid() + "' limit 101";

        assertSql( sql.getValue(), expected );
    }

    @Test
    public void verifyWithProgramStageAndTextualDataElementAndFilter() {

        EventQueryParams params = createRequestParamsWithFilter(programStage, ValueType.TEXT);

        subject.getEnrollments(params, new ListGrid(), 10000);

        verify(jdbcTemplate).queryForRowSet(sql.capture());

        String subSelect = "(select \"fWIAEtYVEGk\" from analytics_event_" + programA.getUid()  + " where analytics_event_"
            + programA.getUid()  + ".pi = ax.pi and \"fWIAEtYVEGk\" is not null and ps = '"
            + programStage.getUid() + "' order by executiondate desc limit 1 )";

        String expected = "ax.\"monthly\",ax.\"ou\"," + subSelect + "  from " + getTable( programA.getUid() )
            + " as ax where ax.\"monthly\" in ('2000Q1') and (uidlevel0 = 'ouabcdefghA' ) "
            + "and ps = '" + programStage.getUid() + "' and lower(" + subSelect + ") > '10' limit 10001";

        assertSql( sql.getValue(), expected );
    }

    @Test
    public void verifyWithProgramStageAndNumericDataElementAndFilter2() {

        EventQueryParams params = createRequestParamsWithFilter(programStage, ValueType.NUMBER);

        subject.getEnrollments(params, new ListGrid(), 10000);

        verify(jdbcTemplate).queryForRowSet(sql.capture());

        String subSelect = "(select \"fWIAEtYVEGk\" from analytics_event_" + programA.getUid()  + " where analytics_event_"
            + programA.getUid()  + ".pi = ax.pi and \"fWIAEtYVEGk\" is not null and ps = '"
            + programStage.getUid() + "' order by executiondate desc limit 1 )";

        String expected = "ax.\"monthly\",ax.\"ou\"," + subSelect + "  from " + getTable( programA.getUid() )
            + " as ax where ax.\"monthly\" in ('2000Q1') and (uidlevel0 = 'ouabcdefghA' ) "
            + "and ps = '" + programStage.getUid() + "' and " + subSelect + " > '10' limit 10001";

        assertSql( sql.getValue(), expected );
    }

    @Test
    public void verifyWithProgramIndicatorAndRelationshipTypeBothSidesTei()
    {
        Date startDate = getDate( 2015, 1, 1 );
        Date endDate = getDate( 2017, 4, 8 );

        String piSubquery = "distinct psi";

        ProgramIndicator programIndicatorA = createProgramIndicator( 'A', programA, "", "" );

        RelationshipType relationshipTypeA = createRelationshipType( RelationshipEntity.TRACKED_ENTITY_INSTANCE );

        EventQueryParams.Builder params = new EventQueryParams.Builder(
            createRequestParams( programIndicatorA, relationshipTypeA ) ).withStartDate( startDate )
                .withEndDate( endDate );

        when( programIndicatorService.getAnalyticsSql( "", programIndicatorA, getDate( 2000, 1, 1 ),
            getDate( 2017, 4, 8 ), "subax" ) ).thenReturn( piSubquery );

        subject.getEnrollments( params.build(), new ListGrid(), 100 );

        verify( jdbcTemplate ).queryForRowSet( sql.capture() );

        String expected = "ax.\"monthly\",ax.\"ou\",(SELECT avg (" + piSubquery + ") FROM analytics_event_"
                + programA.getUid().toLowerCase() + " as subax WHERE  "
                + "ax.tei in (select tei.uid from trackedentityinstance tei "
                + "LEFT JOIN relationshipitem ri on tei.trackedentityinstanceid = ri.trackedentityinstanceid  "
                + "LEFT JOIN relationship r on r.relationshipid = ri.relationshipid "
                + "LEFT JOIN relationshiptype rty on rty.relationshiptypeid = r.relationshiptypeid "
                + "WHERE rty.relationshiptypeid = " + relationshipTypeA.getId() + ")) as \"" + programIndicatorA.getUid()
                + "\"  " + "from analytics_enrollment_" + programA.getUid()
                + " as ax where enrollmentdate >= '2015-01-01' and enrollmentdate <= '2017-04-08' and (uidlevel0 = 'ouabcdefghA' ) limit 101";

        assertSql( sql.getValue(), expected );
    }

    @Test
    public void verifyWithProgramIndicatorAndRelationshipTypeDifferentConstraint()
    {
        Date startDate = getDate( 2015, 1, 1 );
        Date endDate = getDate( 2017, 4, 8 );

        String piSubquery = "distinct psi";

        ProgramIndicator programIndicatorA = createProgramIndicator( 'A', programA, "", "" );

        RelationshipType relationshipTypeA = createRelationshipType( RelationshipEntity.TRACKED_ENTITY_INSTANCE,
            RelationshipEntity.PROGRAM_INSTANCE );

        EventQueryParams.Builder params = new EventQueryParams.Builder(
            createRequestParams( programIndicatorA, relationshipTypeA ) ).withStartDate( startDate )
                .withEndDate( endDate );

        when( programIndicatorService.getAnalyticsSql( "", programIndicatorA, getDate( 2000, 1, 1 ),
            getDate( 2017, 4, 8 ), "subax" ) ).thenReturn( piSubquery );

        subject.getEnrollments( params.build(), new ListGrid(), 100 );

        verify( jdbcTemplate ).queryForRowSet( sql.capture() );

        String expected = "ax.\"monthly\",ax.\"ou\",(SELECT avg (" + piSubquery + ") FROM analytics_event_"
            + programA.getUid().toLowerCase() + " as subax WHERE  "
            + "( ( ax.tei in (select tei.uid from trackedentityinstance tei LEFT JOIN relationshipitem ri on tei.trackedentityinstanceid = ri.trackedentityinstanceid  "
            + "LEFT JOIN relationship r on r.relationshipid = ri.relationshipid LEFT JOIN relationshiptype rty on rty.relationshiptypeid = r.relationshiptypeid "
            + "WHERE rty.relationshiptypeid = " + relationshipTypeA.getId()
            + ")) OR ( ax.pi in (select pi.uid from programinstance pi "
            + "LEFT JOIN relationshipitem ri on pi.programinstanceid = ri.programinstanceid  LEFT JOIN relationship r on r.relationshipid = ri.relationshipid "
            + "LEFT JOIN relationshiptype rty on rty.relationshiptypeid = r.relationshiptypeid WHERE rty.relationshiptypeid = "
            + relationshipTypeA.getId() + ")) )) " + "as \"" + programIndicatorA.getUid() + "\"  "
            + "from analytics_enrollment_" + programA.getUid()
            + " as ax where enrollmentdate >= '2015-01-01' and enrollmentdate <= '2017-04-08' and (uidlevel0 = 'ouabcdefghA' ) limit 101";

        assertSql( sql.getValue(), expected );
    }

    @Override
    String getTableName() {
        return this.TABLE_NAME;
    }

    private RelationshipType createRelationshipType(RelationshipEntity fromConstraint,
                                                    RelationshipEntity toConstraint) {
        RelationshipType relationshipTypeA = new BeanRandomizer().randomObject(RelationshipType.class);

        RelationshipConstraint from = new RelationshipConstraint();
        from.setRelationshipEntity(fromConstraint);

        RelationshipConstraint to = new RelationshipConstraint();
        to.setRelationshipEntity(toConstraint);

        relationshipTypeA.setFromConstraint(from);
        relationshipTypeA.setToConstraint(to);
        return relationshipTypeA;
    }

    private RelationshipType createRelationshipType( RelationshipEntity fromToConstraint )
    {
        return createRelationshipType( fromToConstraint, fromToConstraint );
    }

    private void assertSql( String actual, String expected )
    {
        assertThat( actual, is("select " + DEFAULT_COLUMNS + "," + expected ) );
    }

    @Test
    public void verifyWithProgramIndicatorAndRelationshipTypeBothSidesTei2()
    {
        Date startDate = getDate( 2015, 1, 1 );
        Date endDate = getDate( 2017, 4, 8 );
        Program programB = createProgram( 'B' );
        String piSubquery = "distinct psi";

        ProgramIndicator programIndicatorA = createProgramIndicator( 'A', programB, "", "" );

        RelationshipType relationshipTypeA = createRelationshipType( RelationshipEntity.TRACKED_ENTITY_INSTANCE );

        EventQueryParams.Builder params = new EventQueryParams.Builder(
            createRequestParams( programIndicatorA, relationshipTypeA ) ).withStartDate( startDate )
                .withEndDate( endDate );

        when( programIndicatorService.getAnalyticsSql( "", programIndicatorA, getDate( 2000, 1, 1 ),
            getDate( 2017, 4, 8 ), "subax" ) ).thenReturn( piSubquery );

        subject.getEnrollments( params.build(), new ListGrid(), 100 );

        verify( jdbcTemplate ).queryForRowSet( sql.capture() );

        String expected = "ax.\"monthly\",ax.\"ou\",(SELECT avg (" + piSubquery + ") FROM analytics_event_"
            + programB.getUid().toLowerCase() + " as subax WHERE  "
            + "ax.tei in (select tei.uid from trackedentityinstance tei "
            + "LEFT JOIN relationshipitem ri on tei.trackedentityinstanceid = ri.trackedentityinstanceid  "
            + "LEFT JOIN relationship r on r.relationshipid = ri.relationshipid "
            + "LEFT JOIN relationshiptype rty on rty.relationshiptypeid = r.relationshiptypeid "
            + "WHERE rty.relationshiptypeid = " + relationshipTypeA.getId() + ")) as \"" + programIndicatorA.getUid()
            + "\"  " + "from analytics_enrollment_" + programA.getUid()
            + " as ax where enrollmentdate >= '2015-01-01' and enrollmentdate <= '2017-04-08' and (uidlevel0 = 'ouabcdefghA' ) limit 101";

        assertSql( sql.getValue(), expected );
    }
}
