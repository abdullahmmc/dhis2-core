package org.hisp.dhis.dxf2.events.event;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.collect.ImmutableMap;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hisp.dhis.common.IdScheme;
import org.hisp.dhis.common.IdSchemes;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.QueryFilter;
import org.hisp.dhis.common.QueryItem;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.commons.util.SqlHelper;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dxf2.events.enrollment.EnrollmentStatus;
import org.hisp.dhis.dxf2.events.report.EventRow;
import org.hisp.dhis.dxf2.events.trackedentity.Attribute;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.eventdatavalue.EventDataValue;
import org.hisp.dhis.hibernate.jsonb.type.JsonEventDataValueSetBinaryType;
import org.hisp.dhis.jdbc.StatementBuilder;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramStatus;
import org.hisp.dhis.program.ProgramType;
import org.hisp.dhis.query.Order;
import org.hisp.dhis.security.acl.AccessStringHelper;
import org.hisp.dhis.user.CurrentUserService;
import org.hisp.dhis.user.User;
import org.hisp.dhis.util.DateUtils;
import org.hisp.dhis.util.ObjectUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hisp.dhis.common.IdentifiableObjectUtils.getIdentifiers;
import static org.hisp.dhis.commons.util.TextUtils.*;
import static org.hisp.dhis.dxf2.events.event.AbstractEventService.STATIC_EVENT_COLUMNS;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.*;
import static org.hisp.dhis.util.DateUtils.*;

/**
 * @author Morten Olav Hansen <mortenoh@gmail.com>
 */
@Repository( "org.hisp.dhis.dxf2.events.event.EventStore" )
public class JdbcEventStore
        implements EventStore
{
    private static final Log log = LogFactory.getLog( JdbcEventStore.class );

    private static final Map<String, String> QUERY_PARAM_COL_MAP = ImmutableMap.<String, String>builder()
        .put( "event", "psi_uid" ).put( "program", "p_uid" ).put( "programStage", "ps_uid" )
        .put( "enrollment", "pi_uid" ).put( "enrollmentStatus", "pi_status" ).put( "orgUnit", "ou_uid" )
        .put( "orgUnitName", "ou_name" ).put( "trackedEntityInstance", "tei_uid" )
        .put( "eventDate", "psi_executiondate" ).put( "followup", "pi_followup" ).put( "status", "psi_status" )
        .put( "dueDate", "psi_duedate" ).put( "storedBy", "psi_storedby" ).put( "created", "psi_created" )
        .put( "lastUpdated", "psi_lastupdated" ).put( "completedBy", "psi_completedby" )
        .put( "attributeOptionCombo", "psi_aoc" ).put( "completedDate", "psi_completeddate" )
        .put( "deleted", "psi_deleted" ).build();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    //Cannot use DefaultRenderService mapper. Does not work properly - DHIS2-6102
    private static final ObjectReader eventDataValueJsonReader =
            JsonEventDataValueSetBinaryType.MAPPER.readerFor( new TypeReference<Map<String, EventDataValue>>() {} );

    private final StatementBuilder statementBuilder;

    private final JdbcTemplate jdbcTemplate;

    private final CurrentUserService currentUserService;

    private final IdentifiableObjectManager manager;

    public JdbcEventStore( StatementBuilder statementBuilder, @Qualifier( "readOnlyJdbcTemplate" ) JdbcTemplate jdbcTemplate,
        CurrentUserService currentUserService, IdentifiableObjectManager identifiableObjectManager )
    {
        checkNotNull( statementBuilder );
        checkNotNull( jdbcTemplate );
        checkNotNull( currentUserService );
        checkNotNull( identifiableObjectManager );

        this.statementBuilder = statementBuilder;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.manager = identifiableObjectManager;
    }

    // -------------------------------------------------------------------------
    // EventStore implementation
    // -------------------------------------------------------------------------

    @Override
    public List<Event> getEvents( EventSearchParams params, List<OrganisationUnit> organisationUnits, Map<String, Set<String>> psdesWithSkipSyncTrue )
    {
        User user = currentUserService.getCurrentUser();

        boolean isSuperUser = isSuper( user );

        if ( !isSuperUser )
        {
            params.setAccessiblePrograms( manager.getDataReadAll( Program.class )
                .stream().map( Program::getUid ).collect( Collectors.toSet() ) );

            params.setAccessibleProgramStages( manager.getDataReadAll( ProgramStage.class )
                .stream().map( ProgramStage::getUid ).collect( Collectors.toSet() ) );
        }

        Map<String, Event> eventUidToEventMap = new HashMap<>( params.getPageSizeWithDefault() );
        List<Event> events = new ArrayList<>();

        String sql = buildSql( params, organisationUnits, user );

        SqlRowSet rowSet = jdbcTemplate.queryForRowSet( sql );

        log.debug( "Event query SQL: " + sql );

        Set<String> notes = new HashSet<>();

        IdSchemes idSchemes = ObjectUtils.firstNonNull( params.getIdSchemes(), new IdSchemes() );

        while ( rowSet.next() )
        {
            if ( rowSet.getString( "psi_uid" ) == null || (params.getCategoryOptionCombo() == null && !isSuperUser && !userHasAccess( rowSet )) )
            {
                continue;
            }

            String psiUid = rowSet.getString( "psi_uid" );

            Event event;

            if ( !eventUidToEventMap.containsKey( psiUid ) )
            {
                event = new Event();
                eventUidToEventMap.put( psiUid, event );

                if ( !params.isSkipEventId() )
                {
                    event.setUid( psiUid );
                    event.setEvent( IdSchemes.getValue( rowSet.getString( "psi_uid" ), rowSet.getString( "psi_code" ),
                        idSchemes.getProgramStageInstanceIdScheme() ) );
                }

                event.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );
                event.setStatus( EventStatus.valueOf( rowSet.getString( "psi_status" ) ) );

                event.setProgram( IdSchemes.getValue( rowSet.getString( "p_uid" ), rowSet.getString( "p_code" ),
                    idSchemes.getProgramIdScheme() ) );
                event.setProgramStage( IdSchemes.getValue( rowSet.getString( "ps_uid" ), rowSet.getString( "ps_code" ),
                    idSchemes.getProgramStageIdScheme() ) );
                event.setOrgUnit( IdSchemes.getValue( rowSet.getString( "ou_uid" ), rowSet.getString( "ou_code" ),
                    idSchemes.getOrgUnitIdScheme() ) );
                event.setDeleted( rowSet.getBoolean( "psi_deleted" ) );

                ProgramType programType = ProgramType.fromValue( rowSet.getString( "p_type" ) );

                if ( programType != ProgramType.WITHOUT_REGISTRATION )
                {
                    event.setEnrollment( rowSet.getString( "pi_uid" ) );
                    event.setEnrollmentStatus( EnrollmentStatus
                            .fromProgramStatus( ProgramStatus.valueOf( rowSet.getString( "pi_status" ) ) ) );
                    event.setFollowup( rowSet.getBoolean( "pi_followup" ) );
                }

                if ( params.getCategoryOptionCombo() == null && !isSuper( user ) )
                {
                    event.setOptionSize( rowSet.getInt( "option_size" ) );
                }

                event.setAttributeOptionCombo( rowSet.getString( "coc_categoryoptioncombouid" ) );
                event.setAttributeCategoryOptions( rowSet.getString( "deco_uid" ) );
                event.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );

                event.setStoredBy( rowSet.getString( "psi_storedby" ) );
                event.setOrgUnitName( rowSet.getString( "ou_name" ) );
                event.setDueDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_duedate" ) ) );
                event.setEventDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_executiondate" ) ) );
                event.setCreated( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_created" ) ) );
                event.setLastUpdated( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_lastupdated" ) ) );

                event.setCompletedBy( rowSet.getString( "psi_completedby" ) );
                event.setCompletedDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_completeddate" ) ) );

                if ( rowSet.getObject( "psi_geometry" ) != null )
                {
                    try
                    {
                        Geometry geom = new WKTReader().read( rowSet.getString( "psi_geometry" ) );

                        event.setGeometry( geom );
                        event.setCoordinate( new Coordinate( geom.getCoordinate().x, geom.getCoordinate().y ) );
                    }
                    catch ( ParseException e )
                    {
                        log.error( "Unable to read geometry for event '" + event.getUid() + "': ", e );
                    }
                }

                if ( rowSet.getObject( "user_assigned" ) != null )
                {
                    event.setAssignedUser( rowSet.getString( "user_assigned" ) );
                    event.setAssignedUserUsername( rowSet.getString( "user_assigned_username" ) );
                }

                events.add( event );
            }
            else
            {
                event = eventUidToEventMap.get( psiUid );
                String attributeCategoryCombination = event.getAttributeCategoryOptions();
                String currentAttributeCategoryCombination = rowSet.getString( "deco_uid" );

                if ( !attributeCategoryCombination.contains( currentAttributeCategoryCombination ) )
                {
                    event.setAttributeCategoryOptions(
                        attributeCategoryCombination + ";" + currentAttributeCategoryCombination );
                }
            }

            if ( !org.springframework.util.StringUtils.isEmpty( rowSet.getString( "psi_eventdatavalues" ) ) )
            {
                Set<EventDataValue> eventDataValues = convertEventDataValueJsonIntoSet( rowSet.getString( "psi_eventdatavalues" ) );
                Map<String, String> dataElementsUidToCode =
                    getDataElementsUidToCode( eventDataValues, idSchemes.getDataElementIdScheme() );

                for( EventDataValue dv : eventDataValues )
                {
                    DataValue dataValue = convertEventDataValueIntoDtoDataValue( dv, dataElementsUidToCode,
                        idSchemes.getDataElementIdScheme() );

                    if ( params.isSynchronizationQuery() )
                    {
                        if ( psdesWithSkipSyncTrue.containsKey( rowSet.getString( "ps_uid" ) ) &&
                                psdesWithSkipSyncTrue.get( rowSet.getString( "ps_uid" ) ).contains( dv.getDataElement() ) )
                        {
                            dataValue.setSkipSynchronization( true );
                        }
                        else
                        {
                            dataValue.setSkipSynchronization( false );
                        }
                    }

                    event.getDataValues().add( dataValue );
                }
            }

            if ( rowSet.getString( "psinote_value" ) != null && !notes.contains( rowSet.getString( "psinote_id" ) ) )
            {
                Note note = new Note();
                note.setNote( rowSet.getString( "psinote_uid" ) );
                note.setValue( rowSet.getString( "psinote_value" ) );
                note.setStoredDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psinote_storeddate" ) ) );
                note.setStoredBy( rowSet.getString( "psinote_storedby" ) );

                event.getNotes().add( note );
                notes.add( rowSet.getString( "psinote_id" ) );
            }
        }

        if ( params.getCategoryOptionCombo() == null && !isSuper( user ) )
        {
            return events.stream().filter( ev -> ev.getAttributeCategoryOptions() != null && splitToArray( ev.getAttributeCategoryOptions(), TextUtils.SEMICOLON ).size() == ev.getOptionSize() ).collect( Collectors.toList() );
        }

        return events;
    }

    @Override
    public List<Map<String, String>> getEventsGrid( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        String sql = buildGridSql( params, organisationUnits );

        SqlRowSet rowSet = jdbcTemplate.queryForRowSet( sql );

        log.debug( "Event query SQL: " + sql );

        List<Map<String, String>> list = new ArrayList<>();

        while ( rowSet.next() )
        {
            final Map<String, String> map = new HashMap<>();

            for ( String col : STATIC_EVENT_COLUMNS )
            {
                map.put( col, rowSet.getString( col ) );
            }

            for ( QueryItem item : params.getDataElements() )
            {
                map.put( item.getItemId(), rowSet.getString( item.getItemId() ) );
            }

            list.add( map );
        }

        return list;
    }

    @Override
    public List<EventRow> getEventRows( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        User user = currentUserService.getCurrentUser();

        boolean isSuperUser = isSuper( user );

        if ( !isSuperUser )
        {
            params.setAccessiblePrograms( manager.getDataReadAll( Program.class )
                .stream().map( Program::getUid ).collect( Collectors.toSet() ) );

            params.setAccessibleProgramStages( manager.getDataReadAll( ProgramStage.class )
                .stream().map( ProgramStage::getUid ).collect( Collectors.toSet() ) );
        }

        List<EventRow> eventRows = new ArrayList<>();

        String sql = buildSql( params, organisationUnits, user );

        SqlRowSet rowSet = jdbcTemplate.queryForRowSet( sql );

        log.debug( "Event query SQL: " + sql );

        EventRow eventRow = new EventRow();

        eventRow.setEvent( "not_valid" );

        Set<String> notes = new HashSet<>();

        IdSchemes idSchemes = ObjectUtils.firstNonNull( params.getIdSchemes(), new IdSchemes() );

        Map<String, List<DataValue>> processedDataValues = new HashMap<>();

        while ( rowSet.next() )
        {
            if ( rowSet.getString( "psi_uid" ) == null || ( params.getCategoryOptionCombo() == null && !isSuperUser && !userHasAccess( rowSet ) ) )
            {
                continue;
            }

            if ( eventRow.getUid() == null || !eventRow.getUid().equals( rowSet.getString( "psi_uid" ) ) )
            {
                eventRow = new EventRow();

                eventRow.setUid( rowSet.getString( "psi_uid" ) );

                eventRow.setEvent( IdSchemes.getValue( rowSet.getString( "psi_uid" ), rowSet.getString( "psi_code" ), idSchemes.getProgramStageInstanceIdScheme() ) );
                eventRow.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );
                eventRow.setTrackedEntityInstanceOrgUnit( rowSet.getString( "tei_ou" ) );
                eventRow.setTrackedEntityInstanceOrgUnitName( rowSet.getString( "tei_ou_name" ) );
                eventRow.setTrackedEntityInstanceCreated( rowSet.getString( "tei_created" ) );
                eventRow.setTrackedEntityInstanceInactive( rowSet.getBoolean( "tei_inactive" ) );
                eventRow.setDeleted( rowSet.getBoolean( "psi_deleted" ) );

                eventRow.setProgram( IdSchemes.getValue( rowSet.getString( "p_uid" ), rowSet.getString( "p_code" ),
                    idSchemes.getProgramIdScheme() ) );
                eventRow.setProgramStage( IdSchemes.getValue( rowSet.getString( "ps_uid" ),
                    rowSet.getString( "ps_code" ), idSchemes.getProgramStageIdScheme() ) );
                eventRow.setOrgUnit( IdSchemes.getValue( rowSet.getString( "ou_uid" ), rowSet.getString( "ou_code" ),
                    idSchemes.getOrgUnitIdScheme() ) );

                ProgramType programType = ProgramType.fromValue( rowSet.getString( "p_type" ) );

                if ( programType == ProgramType.WITHOUT_REGISTRATION )
                {
                    eventRow.setEnrollment( rowSet.getString( "pi_uid" ) );
                    eventRow.setFollowup( rowSet.getBoolean( "pi_followup" ) );
                }

                eventRow.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );
                eventRow.setOrgUnitName( rowSet.getString( "ou_name" ) );
                eventRow.setDueDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_duedate" ) ) );
                eventRow.setEventDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_executiondate" ) ) );

                eventRows.add( eventRow );
            }

            if ( rowSet.getString( "pav_value" ) != null && rowSet.getString( "ta_uid" ) != null )
            {
                String valueType = rowSet.getString( "ta_valuetype" );

                Attribute attribute = new Attribute();
                attribute.setCreated( DateUtils.getIso8601NoTz( rowSet.getDate( "pav_created" ) ) );
                attribute.setLastUpdated( DateUtils.getIso8601NoTz( rowSet.getDate( "pav_lastupdated" ) ) );
                attribute.setValue( rowSet.getString( "pav_value" ) );
                attribute.setDisplayName( rowSet.getString( "ta_name" ) );
                attribute.setValueType( valueType != null ? ValueType.valueOf( valueType.toUpperCase() ) : null );
                attribute.setAttribute( rowSet.getString( "ta_uid" ) );

                eventRow.getAttributes().add( attribute );
            }

            if ( !org.springframework.util.StringUtils.isEmpty( rowSet.getString( "psi_eventdatavalues" ) )
                    && !processedDataValues.containsKey( rowSet.getString( "psi_uid" ) ) )
            {
                List<DataValue> dataValues = new ArrayList<>();
                Set<EventDataValue> eventDataValues = convertEventDataValueJsonIntoSet( rowSet.getString( "psi_eventdatavalues" ) );
                Map<String, String> dataElementsUidToCode =
                    getDataElementsUidToCode( eventDataValues, idSchemes.getDataElementIdScheme() );

                for( EventDataValue dv : eventDataValues )
                {
                    dataValues.add( convertEventDataValueIntoDtoDataValue( dv, dataElementsUidToCode,
                        idSchemes.getDataElementIdScheme() ) );
                }
                processedDataValues.put(rowSet.getString( "psi_uid"), dataValues);
            }

            if ( rowSet.getString( "psinote_value" ) != null && !notes.contains( rowSet.getString( "psinote_id" ) ) )
            {
                Note note = new Note();
                note.setNote( rowSet.getString( "psinote_uid" ) );
                note.setValue( rowSet.getString( "psinote_value" ) );
                note.setStoredDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psinote_storeddate" ) ) );
                note.setStoredBy( rowSet.getString( "psinote_storedby" ) );

                eventRow.getNotes().add( note );
                notes.add( rowSet.getString( "psinote_id" ) );
            }
        }
        eventRows.forEach(e -> e.setDataValues( processedDataValues.get( e.getUid())));
        return eventRows;
    }

    private Map<String, String> getDataElementsUidToCode( Set<EventDataValue> eventDataValues, IdScheme idScheme )
    {
        //Get mapping only when needed
        if ( idScheme != IdScheme.ID && idScheme != IdScheme.UID )
        {
            Map<String, String> dataElementsUidToCode = new HashMap<>();

            List<String> dataElementUids = eventDataValues.stream()
                .map( EventDataValue::getDataElement )
                .collect( Collectors.toList() );

            List<DataElement> dataElements = manager.getByUid( DataElement.class, dataElementUids );

            dataElements.forEach( de -> {
                dataElementsUidToCode.put( de.getUid(), de.getCode() );
            } );

            return dataElementsUidToCode;
        }

        return Collections.emptyMap();
    }

    @Override
    public int getEventCount( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        User user = currentUserService.getCurrentUser();

        boolean isSuperUser = isSuper( user );

        if ( !isSuperUser )
        {
            params.setAccessiblePrograms( manager.getDataReadAll( Program.class )
                    .stream().map( Program::getUid ).collect( Collectors.toSet() ) );

            params.setAccessibleProgramStages( manager.getDataReadAll( ProgramStage.class )
                    .stream().map( ProgramStage::getUid ).collect( Collectors.toSet() ) );
        }

        String sql;

        if ( params.hasFilters() )
        {
            sql = buildGridSql( params, organisationUnits );
        }
        else
        {
            sql = getEventSelectQuery( params, organisationUnits, user );
        }

        sql = sql.replaceFirst( "select .*? from", "select count(*) from" );

        sql = sql.replaceFirst( "order .*? (desc|asc)", "" );

        sql = sql.replaceFirst( "limit \\d+ offset \\d+", "" );

        log.debug( "Event query count SQL: " + sql );

        return jdbcTemplate.queryForObject( sql, Integer.class );
    }

    private DataValue convertEventDataValueIntoDtoDataValue( EventDataValue eventDataValue,
        Map<String, String> dataElementsUidToCode, IdScheme idScheme )
    {
        DataValue dataValue = new DataValue();
        dataValue.setCreated( DateUtils.getIso8601NoTz( eventDataValue.getCreated() ) );
        dataValue.setLastUpdated( DateUtils.getIso8601NoTz( eventDataValue.getLastUpdated() ) );
        dataValue.setValue( eventDataValue.getValue() );
        dataValue.setProvidedElsewhere( eventDataValue.getProvidedElsewhere() );
        dataValue.setStoredBy( eventDataValue.getStoredBy() );

        String deUid = eventDataValue.getDataElement();
        dataValue.setDataElement( IdSchemes.getValue( deUid, dataElementsUidToCode.get( deUid ), idScheme ) );

        return dataValue;
    }

    private String buildGridSql( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        SqlHelper hlp = new SqlHelper();

        // ---------------------------------------------------------------------
        // Select clause
        // ---------------------------------------------------------------------

        String sql = "select psi.uid as " + EVENT_ID + ", " + "psi.created as " + EVENT_CREATED_ID + ", "
                + "psi.lastupdated as " + EVENT_LAST_UPDATED_ID + ", " + "psi.storedby as " + EVENT_STORED_BY_ID + ", "
                + "psi.completedby as " + EVENT_COMPLETED_BY_ID + ", " + "psi.completeddate as " + EVENT_COMPLETED_DATE_ID
                + ", " + "psi.duedate as " + EVENT_DUE_DATE_ID + ", " + "psi.executiondate as " + EVENT_EXECUTION_DATE_ID
                + ", " + "ou.uid as " + EVENT_ORG_UNIT_ID + ", " + "ou.name as " + EVENT_ORG_UNIT_NAME + ", "
                + "psi.status as " + EVENT_STATUS_ID + ", "
                + "pi.uid as " + EVENT_ENROLLMENT_ID + ", "
                + "ps.uid as " + EVENT_PROGRAM_STAGE_ID + ", " + "p.uid as "
                + EVENT_PROGRAM_ID + ", " + "coc.uid as " + EVENT_ATTRIBUTE_OPTION_COMBO_ID + ", " + "psi.deleted as " + EVENT_DELETED + ", "
                + "psi.geometry as " + EVENT_GEOMETRY + ", ";

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            String queryCol = item.isNumeric() ? "CAST( " + dataValueValueSql + " AS NUMERIC ) " : dataValueValueSql;
            queryCol += " as " + col + ", ";

            sql += queryCol;
        }

        sql = removeLastComma( sql ) + " ";

        // ---------------------------------------------------------------------
        // From and where clause
        // ---------------------------------------------------------------------

        sql += getFromWhereClause( params, hlp, organisationUnits );

        // ---------------------------------------------------------------------
        // Order clause
        // ---------------------------------------------------------------------

        sql += getGridOrderQuery( params );

        // ---------------------------------------------------------------------
        // Paging clause
        // ---------------------------------------------------------------------

        sql += getEventPagingQuery( params );

        return sql;
    }

    /**
     * Query is based on three sub queries on event, data value and comment,
     * which are joined using program stage instance id. The purpose of the
     * separate queries is to be able to page properly on events.
     */
    private String buildSql( EventSearchParams params, List<OrganisationUnit> organisationUnits, User user )
    {
        String sql = "select * from (";

        sql += getEventSelectQuery( params, organisationUnits, user );

        sql += getOrderQuery( params );

        sql += getEventPagingQuery( params );

        sql += ") as event left join (";

        if ( params.isIncludeAttributes() )
        {
            sql += getAttributeValueQuery();

            sql += ") as att on event.tei_id=att.pav_id left join (";
        }

        sql += getCommentQuery();

        sql += ") as cm on event.psi_id=cm.psic_id ";

        sql += getOrderQuery( params );

        return sql;
    }

    private String getEventSelectQuery( EventSearchParams params, List<OrganisationUnit> organisationUnits, User user )
    {
        List<Long> orgUnitIds = getIdentifiers( organisationUnits );

        SqlHelper hlp = new SqlHelper();

        String sql = "select psi.programstageinstanceid as psi_id, psi.uid as psi_uid, psi.code as psi_code, psi.status as psi_status, psi.executiondate as psi_executiondate, "
            + "psi.eventdatavalues as psi_eventdatavalues, psi.duedate as psi_duedate, psi.completedby as psi_completedby, psi.storedby as psi_storedby, "
            + "psi.created as psi_created, psi.lastupdated as psi_lastupdated, psi.completeddate as psi_completeddate, psi.deleted as psi_deleted, "
            + "ST_AsText( psi.geometry ) as psi_geometry, au.uid as user_assigned, auc.username as user_assigned_username, "
            + "coc.categoryoptioncomboid AS coc_categoryoptioncomboid, coc.code AS coc_categoryoptioncombocode, coc.uid AS coc_categoryoptioncombouid, cocco.categoryoptionid AS cocco_categoryoptionid, deco.uid AS deco_uid, ";

        if ( (params.getCategoryOptionCombo() == null || params.getCategoryOptionCombo().isDefault()) && !isSuper( user ) )
        {
            sql += "deco.publicaccess AS deco_publicaccess, decoa.uga_access AS uga_access, decoa.ua_access AS ua_access, cocount.option_size AS option_size, ";
        }

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            String queryCol = item.isNumeric() ? " CAST( " + dataValueValueSql + " AS NUMERIC)" : "lower(" + dataValueValueSql + ")";
            queryCol += " as " + col + ", ";

            sql += queryCol;
        }

        sql += "pi.uid as pi_uid, pi.status as pi_status, pi.followup as pi_followup, p.uid as p_uid, p.code as p_code, "
            + "coc.categoryoptioncomboid AS coc_categoryoptioncomboid, coc.code AS coc_categoryoptioncombocode, coc.uid AS coc_categoryoptioncombouid, cocco.categoryoptionid AS cocco_categoryoptionid, "
            + "deco.uid AS deco_uid, pi.uid as pi_uid, pi.status as pi_status, pi.followup as pi_followup, p.uid as p_uid, p.code as p_code, "
            + "p.type as p_type, ps.uid as ps_uid, ps.code as ps_code, "
            + "ou.uid as ou_uid, ou.code as ou_code, ou.name as ou_name, "
            + "tei.trackedentityinstanceid as tei_id, tei.uid as tei_uid, teiou.uid as tei_ou, teiou.name as tei_ou_name, tei.created as tei_created, tei.inactive as tei_inactive "
            + "from programstageinstance psi "
            + "inner join programinstance pi on pi.programinstanceid=psi.programinstanceid "
            + "inner join program p on p.programid=pi.programid "
            + "inner join programstage ps on ps.programstageid=psi.programstageid "
            + "inner join categoryoptioncombo coc on coc.categoryoptioncomboid=psi.attributeoptioncomboid "
            + "inner join categoryoptioncombos_categoryoptions cocco on psi.attributeoptioncomboid=cocco.categoryoptioncomboid "
            + "inner join dataelementcategoryoption deco on cocco.categoryoptionid=deco.categoryoptionid "
            + "left join trackedentityinstance tei on tei.trackedentityinstanceid=pi.trackedentityinstanceid "
            + "left join organisationunit ou on (psi.organisationunitid=ou.organisationunitid) "
            + "left join organisationunit teiou on (tei.organisationunitid=teiou.organisationunitid) "
            + "left join users auc on (psi.assigneduserid=auc.userid) "
            + "left join userinfo au on (auc.userid=au.userinfoid) ";

        Set<String> joinedColumns = new HashSet<>();

        String eventDataValuesWhereSql = "";

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String optCol = item.getItemId() + "opt";
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            if ( !joinedColumns.contains( col ) )
            {
                if ( item.hasOptionSet() && item.hasFilter() )
                {
                    sql += "inner join optionvalue as " + optCol + " on lower(" + optCol + ".code) = " +
                        "lower(" + dataValueValueSql + ") and " + optCol + ".optionsetid = " + item.getOptionSet().getId() + " ";
                }

                joinedColumns.add( col );
            }

            if ( item.hasFilter() )
            {
                for ( QueryFilter filter : item.getFilters() )
                {
                    final String encodedFilter = statementBuilder.encode( filter.getFilter(), false );

                    final String queryCol = item.isNumeric() ? " CAST( " + dataValueValueSql + " AS NUMERIC)" : "lower( " + dataValueValueSql + " )";

                    if ( !item.hasOptionSet() )
                    {
                        if ( !eventDataValuesWhereSql.isEmpty() )
                        {
                            eventDataValuesWhereSql += " and ";
                        }

                        eventDataValuesWhereSql += " " + queryCol + " " + filter.getSqlOperator() + " "
                            + StringUtils.lowerCase( StringUtils.isNumeric( encodedFilter ) ? encodedFilter :
                                filter.getSqlFilter( encodedFilter ) ) + " ";
                    }
                    else if ( QueryOperator.IN.getValue().equalsIgnoreCase( filter.getSqlOperator() ) )
                    {
                        sql += "and " + queryCol + " " + filter.getSqlOperator() + " "
                            + StringUtils.lowerCase( StringUtils.isNumeric( encodedFilter ) ? encodedFilter :
                                filter.getSqlFilter( encodedFilter ) ) + " ";
                    }
                    else
                    {
                        sql += "and lower(" + optCol + ".name)" + " " + filter.getSqlOperator() + " "
                            + StringUtils.lowerCase( StringUtils.isNumeric( encodedFilter ) ? encodedFilter :
                                filter.getSqlFilter( encodedFilter ) ) + " ";
                    }
                }
            }
        }

        if ( (params.getCategoryOptionCombo() == null || params.getCategoryOptionCombo().isDefault()) && !isSuper( user ) )
        {
            sql += getCategoryOptionSharingForUser( user );
        }

        if ( !eventDataValuesWhereSql.isEmpty() )
        {
            sql += hlp.whereAnd() + eventDataValuesWhereSql + " ";
        }

        if ( params.getTrackedEntityInstance() != null )
        {
            sql += hlp.whereAnd() + " tei.trackedentityinstanceid=" + params.getTrackedEntityInstance().getId() + " ";
        }

        if ( params.getProgram() != null )
        {
            sql += hlp.whereAnd() + " p.programid = " + params.getProgram().getId() + " ";
        }

        if ( params.getProgramStage() != null )
        {
            sql += hlp.whereAnd() + " ps.programstageid = " + params.getProgramStage().getId() + " ";
        }

        if ( params.getProgramStatus() != null )
        {
            sql += hlp.whereAnd() + " pi.status = '" + params.getProgramStatus() + "' ";
        }

        if ( params.getFollowUp() != null )
        {
            sql += hlp.whereAnd() + " pi.followup is " + (params.getFollowUp() ? "true" : "false") + " ";
        }

        sql += addLastUpdatedFilters( params, hlp, true );

        //Comparing milliseconds instead of always creating new Date( 0 );
        if ( params.getSkipChangedBefore() != null && params.getSkipChangedBefore().getTime() > 0 )
        {
            String skipChangedBefore = DateUtils.getLongDateString( params.getSkipChangedBefore() );
            sql += hlp.whereAnd() + " psi.lastupdated >= '" + skipChangedBefore + "' ";
        }

        if ( params.getCategoryOptionCombo() != null )
        {
            sql += hlp.whereAnd() + " psi.attributeoptioncomboid = " + params.getCategoryOptionCombo().getId() + " ";
        }

        if ( orgUnitIds != null && !orgUnitIds.isEmpty() )
        {
            sql += hlp.whereAnd() + " psi.organisationunitid in (" + getCommaDelimitedString( orgUnitIds ) + ") ";
        }

        if ( params.getStartDate() != null )
        {
            sql += hlp.whereAnd() + " (psi.executiondate >= '" + getMediumDateString( params.getStartDate() ) + "' "
                + "or (psi.executiondate is null and psi.duedate >= '" + getMediumDateString( params.getStartDate() )
                + "')) ";
        }

        if ( params.getEndDate() != null )
        {
            Date dateAfterEndDate = getDateAfterAddition( params.getEndDate(), 1 );
            sql += hlp.whereAnd() + " (psi.executiondate < '" + getMediumDateString( dateAfterEndDate ) + "' "
                + "or (psi.executiondate is null and psi.duedate < '" + getMediumDateString( dateAfterEndDate )
                + "')) ";
        }

        if ( params.getProgramType() != null )
        {
            sql += hlp.whereAnd() + " p.type = '" + params.getProgramType() + "' ";
        }

        if ( params.getEventStatus() != null )
        {
            if ( params.getEventStatus() == EventStatus.VISITED )
            {
                sql += hlp.whereAnd() + " psi.status = '" + EventStatus.ACTIVE.name()
                    + "' and psi.executiondate is not null ";
            }
            else if ( params.getEventStatus() == EventStatus.OVERDUE )
            {
                sql += hlp.whereAnd() + " date(now()) > date(psi.duedate) and psi.status = '"
                    + EventStatus.SCHEDULE.name() + "' ";
            }
            else
            {
                sql += hlp.whereAnd() + " psi.status = '" + params.getEventStatus().name() + "' ";
            }
        }

        if ( params.getEvents() != null && !params.getEvents().isEmpty() && !params.hasFilters() )
        {
            sql += hlp.whereAnd() + " (psi.uid in (" + getQuotedCommaDelimitedString( params.getEvents() ) + ")) ";
        }

        if ( params.hasAssignedUsers() )
        {
            sql += hlp.whereAnd() + " (au.uid in (" + getQuotedCommaDelimitedString( params.getAssignedUsers() ) + ")) ";
        }

        if ( params.isIncludeOnlyUnassignedEvents() )
        {
            sql += hlp.whereAnd() + " (au.uid is null) ";
        }

        if ( params.isIncludeOnlyAssignedEvents() )
        {
            sql += hlp.whereAnd() + " (au.uid is not null) ";
        }

        if ( !params.isIncludeDeleted() )
        {
            sql += hlp.whereAnd() + " psi.deleted is false ";
        }

        if ( params.hasSecurityFilter() )
        {
            sql += hlp.whereAnd() + " (p.uid in (" + getQuotedCommaDelimitedString( params.getAccessiblePrograms() ) + ")) ";
            sql += hlp.whereAnd() + " (ps.uid in (" + getQuotedCommaDelimitedString( params.getAccessibleProgramStages() ) + ")) ";
        }

        if ( params.isSynchronizationQuery() )
        {
            sql += hlp.whereAnd() + " psi.lastupdated > psi.lastsynchronized ";
        }

        return sql;
    }

    /**
     * From, join and where clause. For dataElement params, restriction is set
     * in inner join. For query params, restriction is set in where clause.
     */
    private String getFromWhereClause( EventSearchParams params, SqlHelper hlp,
                                       List<OrganisationUnit> organisationUnits )
    {
        String sql = "from programstageinstance psi "
            + "inner join programinstance pi on pi.programinstanceid = psi.programinstanceid "
            + "inner join program p on p.programid = pi.programid "
            + "inner join programstage ps on ps.programstageid = psi.programstageid "
            + "inner join categoryoptioncombo coc on coc.categoryoptioncomboid = psi.attributeoptioncomboid "
            + "inner join organisationunit ou on psi.organisationunitid = ou.organisationunitid "
            + "left join users auc on (psi.assigneduserid=auc.userid) "
            + "left join userinfo au on (auc.userid=au.userinfoid) ";

        Set<String> joinedColumns = new HashSet<>();

        String eventDataValuesWhereSql = "";

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String optCol = item.getItemId() + "opt";
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            if ( !joinedColumns.contains( col ) )
            {
                if ( item.hasOptionSet() && item.hasFilter() )
                {
                    sql += "inner join optionvalue as " + optCol + " on lower(" + optCol + ".code) = " +
                            "lower(" + dataValueValueSql + ") and " + optCol + ".optionsetid = " + item.getOptionSet().getId() + " ";
                }

                joinedColumns.add( col );
            }

            if ( item.hasFilter() )
            {
                for ( QueryFilter filter : item.getFilters() )
                {
                    final String encodedFilter = statementBuilder.encode( filter.getFilter(), false );

                    final String queryCol = item.isNumeric() ? " CAST( " + dataValueValueSql + " AS NUMERIC)" : "lower(" + dataValueValueSql + ")";

                    if ( !item.hasOptionSet() )
                    {
                        if ( !eventDataValuesWhereSql.isEmpty() )
                        {
                            eventDataValuesWhereSql += " and ";
                        }

                        eventDataValuesWhereSql += " " + queryCol + " " + filter.getSqlOperator() + " "
                                + StringUtils.lowerCase( filter.getSqlFilter( encodedFilter ) ) + " ";
                    }
                    else if ( QueryOperator.IN.getValue().equalsIgnoreCase( filter.getSqlOperator() ) )
                    {
                        sql += "and " + queryCol + " " + filter.getSqlOperator() + " "
                            + StringUtils.lowerCase( filter.getSqlFilter( encodedFilter ) ) + " ";
                    }
                    else
                    {
                        sql += "and lower( " + optCol + ".name)" + " " + filter.getSqlOperator() + " "
                            + StringUtils.lowerCase( filter.getSqlFilter( encodedFilter ) ) + " ";
                    }
                }
            }
        }

        if ( !eventDataValuesWhereSql.isEmpty() )
        {
            sql += hlp.whereAnd() + eventDataValuesWhereSql + " ";
        }

        if ( organisationUnits != null && !organisationUnits.isEmpty() )
        {
            sql += hlp.whereAnd() + " psi.organisationunitid in ("
                    + getCommaDelimitedString( getIdentifiers( organisationUnits ) ) + ") ";
        }

        if ( params.getProgramStage() != null )
        {
            sql += hlp.whereAnd() + " ps.programstageid = " + params.getProgramStage().getId() + " ";
        }

        if ( params.getCategoryOptionCombo() != null )
        {
            sql += hlp.whereAnd() + " psi.attributeoptioncomboid = " + params.getCategoryOptionCombo().getId() + " ";
        }

        if ( params.getStartDate() != null )
        {
            sql += hlp.whereAnd() + " (psi.executiondate >= '" + getMediumDateString( params.getStartDate() ) + "' "
                + "or (psi.executiondate is null and psi.duedate >= '" + getMediumDateString( params.getStartDate() )
                + "')) ";
        }

        if ( params.getEndDate() != null )
        {
            sql += hlp.whereAnd() + " (psi.executiondate <= '" + getMediumDateString( params.getEndDate() ) + "' "
                + "or (psi.executiondate is null and psi.duedate <= '" + getMediumDateString( params.getEndDate() )
                + "')) ";
        }

        sql += addLastUpdatedFilters( params, hlp, false );

        if ( params.isSynchronizationQuery() )
        {
            sql += hlp.whereAnd() + " psi.lastupdated > psi.lastsynchronized ";
        }

        // Comparing milliseconds instead of always creating new Date( 0 )

        if ( params.getSkipChangedBefore() != null && params.getSkipChangedBefore().getTime() > 0 )
        {
            String skipChangedBefore = DateUtils.getLongDateString( params.getSkipChangedBefore() );
            sql += hlp.whereAnd() + " psi.lastupdated >= '" + skipChangedBefore + "' ";
        }

        if ( params.getDueDateStart() != null )
        {
            sql += hlp.whereAnd() + " psi.duedate is not null and psi.duedate >= '"
                + DateUtils.getLongDateString( params.getDueDateStart() ) + "' ";
        }

        if ( params.getDueDateEnd() != null )
        {
            sql += hlp.whereAnd() + " psi.duedate is not null and psi.duedate <= '"
                + DateUtils.getLongDateString( params.getDueDateEnd() ) + "' ";
        }

        if ( !params.isIncludeDeleted() )
        {
            sql += hlp.whereAnd() + " psi.deleted is false ";
        }

        if ( params.getEventStatus() != null )
        {
            if ( params.getEventStatus() == EventStatus.VISITED )
            {
                sql += hlp.whereAnd() + " psi.status = '" + EventStatus.ACTIVE.name()
                    + "' and psi.executiondate is not null ";
            }
            else if ( params.getEventStatus() == EventStatus.OVERDUE )
            {
                sql += hlp.whereAnd() + " date(now()) > date(psi.duedate) and psi.status = '"
                    + EventStatus.SCHEDULE.name() + "' ";
            }
            else
            {
                sql += hlp.whereAnd() + " psi.status = '" + params.getEventStatus().name() + "' ";
            }
        }

        if ( params.getEvents() != null && !params.getEvents().isEmpty() && !params.hasFilters() )
        {
            sql += hlp.whereAnd() + " (psi.uid in (" + getQuotedCommaDelimitedString( params.getEvents() ) + ")) ";
        }

        if ( params.hasAssignedUsers() )
        {
            sql += hlp.whereAnd() + " (au.uid in (" + getQuotedCommaDelimitedString( params.getAssignedUsers() ) + ")) ";
        }

        if ( params.isIncludeOnlyUnassignedEvents() )
        {
            sql += hlp.whereAnd() + " (au.uid is null) ";
        }

        if ( params.isIncludeOnlyAssignedEvents() )
        {
            sql += hlp.whereAnd() + " (au.uid is not null) ";
        }

        return sql;
    }

    private String addLastUpdatedFilters( EventSearchParams params, SqlHelper hlp, boolean useDateAfterEndDate )
    {
        String sql = "";

        if ( params.hasLastUpdatedDuration() )
        {
            sql += hlp.whereAnd() + " psi.lastupdated >= '"
                + getLongGmtDateString( DateUtils.nowMinusDuration( params.getLastUpdatedDuration() ) ) + "' ";
        }
        else
        {
            if ( params.hasLastUpdatedStartDate() )
            {
                sql += hlp.whereAnd() + " psi.lastupdated >= '"
                    + DateUtils.getLongDateString( params.getLastUpdatedStartDate() ) + "' ";
            }

            if ( params.hasLastUpdatedEndDate() )
            {
                if ( useDateAfterEndDate )
                {
                    Date dateAfterEndDate = getDateAfterAddition( params.getLastUpdatedEndDate(), 1 );
                    sql += hlp.whereAnd() + " psi.lastupdated < '"
                        + DateUtils.getLongDateString( dateAfterEndDate ) + "' ";
                }
                else
                {
                    sql += hlp.whereAnd() + " psi.lastupdated <= '"
                        + DateUtils.getLongDateString( params.getLastUpdatedEndDate() ) + "' ";
                }
            }
        }

        return sql;
    }

    private String getCategoryOptionSharingForUser( User user )
    {
        List<Long> userGroupIds = getIdentifiers( user.getGroups() );

        String sql = " left join ( ";

        sql += "select categoryoptioncomboid, count(categoryoptioncomboid) as option_size from categoryoptioncombos_categoryoptions group by categoryoptioncomboid) "
            + "as cocount on coc.categoryoptioncomboid = cocount.categoryoptioncomboid "
            + "left join ("
            + "select deco.categoryoptionid as deco_id, deco.uid as deco_uid, deco.publicaccess AS deco_publicaccess, "
            + "couga.usergroupaccessid as uga_id, coua.useraccessid as ua_id, uga.access as uga_access, uga.usergroupid AS usrgrp_id, "
            + "ua.access as ua_access, ua.userid as usr_id from dataelementcategoryoption deco "
            + "left join dataelementcategoryoptionusergroupaccesses couga on deco.categoryoptionid = couga.categoryoptionid "
            + "left join dataelementcategoryoptionuseraccesses coua on deco.categoryoptionid = coua.categoryoptionid "
            + "left join usergroupaccess uga on couga.usergroupaccessid = uga.usergroupaccessid "
            + "left join useraccess ua on coua.useraccessid = ua.useraccessid "
            + "where ua.userid = " + user.getId();

        if ( userGroupIds != null && !userGroupIds.isEmpty() )
        {
            sql += " or uga.usergroupid in (" + getCommaDelimitedString( userGroupIds ) + ") ";
        }

        sql += " ) as decoa on cocco.categoryoptionid = decoa.deco_id ";

        return sql;
    }


    private String getEventPagingQuery( EventSearchParams params )
    {
        String sql = " ";

        if ( params.isPaging() )
        {
            sql += "limit " + params.getPageSizeWithDefault() + " offset " + params.getOffset() + " ";
        }

        return sql;
    }

    private String getCommentQuery()
    {
        String sql = "select psic.programstageinstanceid as psic_id, psinote.trackedentitycommentid as psinote_id, psinote.commenttext as psinote_value, "
            + "psinote.created as psinote_storeddate, psinote.creator as psinote_storedby, psinote.uid as psinote_uid "
            + "from programstageinstancecomments psic "
            + "inner join trackedentitycomment psinote on psic.trackedentitycommentid=psinote.trackedentitycommentid ";

        return sql;
    }

    private String getGridOrderQuery( EventSearchParams params )
    {

        if ( params.getGridOrders() != null && params.getDataElements() != null && !params.getDataElements().isEmpty()
                && STATIC_EVENT_COLUMNS != null && !STATIC_EVENT_COLUMNS.isEmpty() )
        {
            List<String> orderFields = new ArrayList<>();

            for ( String order : params.getGridOrders() )
            {
                String[] prop = order.split( ":" );

                if ( prop.length == 2 && (prop[1].equals( "desc" ) || prop[1].equals( "asc" )) )
                {
                    if ( STATIC_EVENT_COLUMNS.contains( prop[0] ) )
                    {
                        orderFields.add( prop[0] + " " + prop[1] );
                    }
                    else
                    {
                        Set<QueryItem> queryItems = params.getDataElements();

                        for ( QueryItem item : queryItems )
                        {
                            if ( prop[0].equals( item.getItemId() ) )
                            {
                                orderFields.add( prop[0] + " " + prop[1] );
                                break;
                            }
                        }
                    }
                }
            }

            if ( !orderFields.isEmpty() )
            {
                return "order by " + StringUtils.join( orderFields, ',' );
            }
        }

        return "order by lastUpdated desc ";
    }

    private String getOrderQuery( EventSearchParams params )
    {
        ArrayList<String> orderFields = new ArrayList<String>();

        if ( params.getGridOrders() != null )
        {
            for ( String order : params.getGridOrders() )
            {
                String[] prop = order.split( ":" );

                if ( prop.length == 2 && (prop[1].equals( "desc" ) || prop[1].equals( "asc" )) )
                {
                    Set<QueryItem> items = params.getDataElements();

                    for ( QueryItem item : items )
                    {
                        if ( prop[0].equals( item.getItemId() ) )
                        {
                            orderFields.add( prop[0] + " " + prop[1] );
                            break;
                        }
                    }
                }
            }
        }

        if ( params.getOrders() != null )
        {
            for ( Order order : params.getOrders() )
            {
                if ( QUERY_PARAM_COL_MAP.containsKey( order.getProperty().getName() ) )
                {
                    String orderText = QUERY_PARAM_COL_MAP.get( order.getProperty().getName() );
                    orderText += order.isAscending() ? " asc" : " desc";
                    orderFields.add( orderText );
                }
            }
        }

        if ( !orderFields.isEmpty() )
        {
            return "order by " + StringUtils.join( orderFields, ',' ) + " ";
        }
        else
        {
            return "order by psi_lastupdated desc ";
        }
    }

    private String getAttributeValueQuery()
    {
        String sql = "select pav.trackedentityinstanceid as pav_id, pav.created as pav_created, pav.lastupdated as pav_lastupdated, "
            + "pav.value as pav_value, ta.uid as ta_uid, ta.name as ta_name, ta.valuetype as ta_valuetype "
            + "from trackedentityattributevalue pav "
            + "inner join trackedentityattribute ta on pav.trackedentityattributeid=ta.trackedentityattributeid ";

        return sql;
    }

    private boolean isSuper( User user )
    {
        return user == null || user.isSuper();
    }

    private boolean userHasAccess( SqlRowSet rowSet )
    {
        if ( rowSet.wasNull() )
        {
            return true;
        }

        if ( rowSet.getString( "uga_access" ) == null && rowSet.getString( "ua_access" ) == null && rowSet.getString( "deco_publicaccess" ) == null )
        {
            return false;
        }

        return AccessStringHelper.isEnabled( rowSet.getString( "deco_publicaccess" ), AccessStringHelper.Permission.DATA_READ );
    }

    private Set<EventDataValue> convertEventDataValueJsonIntoSet( String jsonString )
    {
        try
        {
            Map<String, EventDataValue> data = eventDataValueJsonReader.readValue( jsonString );
            return JsonEventDataValueSetBinaryType.convertEventDataValuesMapIntoSet( data );
        }
        catch ( IOException e )
        {
            log.error( "Parsing EventDataValues json string failed. String value: " + jsonString );
            throw new IllegalArgumentException( e );
        }
    }
}
