-- UCP-NG V001 结构基线：2026-10-09，来源本地 ucp-ng（PostgreSQL 16）。
-- 仅用于新空库建立结构；当前库通过 Flyway baseline 标记为版本 1，不重放此文件。
-- 业务数据、账号、菜单和配置随独立完整快照恢复；本文件不包含数据或迁移历史。

--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15 (Debian 16.15-1.pgdg13+2)
-- Dumped by pg_dump version 16.15 (Debian 16.15-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--



SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: act_evt_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_evt_log (
    log_nr_ integer NOT NULL,
    type_ character varying(64),
    proc_def_id_ character varying(64),
    proc_inst_id_ character varying(64),
    execution_id_ character varying(64),
    task_id_ character varying(64),
    time_stamp_ timestamp(6) without time zone NOT NULL,
    user_id_ character varying(255),
    data_ bytea,
    lock_owner_ character varying(255),
    lock_time_ timestamp(6) without time zone,
    is_processed_ smallint DEFAULT 0
);


--
-- Name: act_evt_log_log_nr__seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.act_evt_log_log_nr__seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    MAXVALUE 2147483647
    CACHE 1;


--
-- Name: act_evt_log_log_nr__seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.act_evt_log_log_nr__seq OWNED BY public.act_evt_log.log_nr_;


--
-- Name: act_ge_bytearray; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ge_bytearray (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    name_ character varying(255),
    deployment_id_ character varying(64),
    bytes_ bytea,
    generated_ boolean
);


--
-- Name: act_ge_property; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ge_property (
    name_ character varying(64) NOT NULL,
    value_ character varying(300),
    rev_ integer
);


--
-- Name: act_hi_actinst; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_actinst (
    id_ character varying(64) NOT NULL,
    rev_ integer DEFAULT 1,
    proc_def_id_ character varying(64) NOT NULL,
    proc_inst_id_ character varying(64) NOT NULL,
    execution_id_ character varying(64) NOT NULL,
    act_id_ character varying(255) NOT NULL,
    task_id_ character varying(64),
    call_proc_inst_id_ character varying(64),
    act_name_ character varying(255),
    act_type_ character varying(255) NOT NULL,
    assignee_ character varying(255),
    completed_by_ character varying(255),
    start_time_ timestamp(6) without time zone NOT NULL,
    end_time_ timestamp(6) without time zone,
    transaction_order_ integer,
    duration_ bigint,
    delete_reason_ character varying(4000),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_hi_attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_attachment (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    user_id_ character varying(255),
    name_ character varying(255),
    description_ character varying(4000),
    type_ character varying(255),
    task_id_ character varying(64),
    proc_inst_id_ character varying(64),
    url_ character varying(4000),
    content_id_ character varying(64),
    time_ timestamp(6) without time zone
);


--
-- Name: act_hi_comment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_comment (
    id_ character varying(64) NOT NULL,
    type_ character varying(255),
    time_ timestamp(6) without time zone NOT NULL,
    user_id_ character varying(255),
    task_id_ character varying(64),
    proc_inst_id_ character varying(64),
    action_ character varying(255),
    message_ character varying(4000),
    full_msg_ bytea
);


--
-- Name: act_hi_detail; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_detail (
    id_ character varying(64) NOT NULL,
    type_ character varying(255) NOT NULL,
    proc_inst_id_ character varying(64),
    execution_id_ character varying(64),
    task_id_ character varying(64),
    act_inst_id_ character varying(64),
    name_ character varying(255) NOT NULL,
    var_type_ character varying(64),
    rev_ integer,
    time_ timestamp(6) without time zone NOT NULL,
    bytearray_id_ character varying(64),
    double_ double precision,
    long_ bigint,
    text_ character varying(4000),
    text2_ character varying(4000)
);


--
-- Name: act_hi_entitylink; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_entitylink (
    id_ character varying(64) NOT NULL,
    link_type_ character varying(255),
    create_time_ timestamp(6) without time zone,
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    parent_element_id_ character varying(255),
    ref_scope_id_ character varying(255),
    ref_scope_type_ character varying(255),
    ref_scope_definition_id_ character varying(255),
    root_scope_id_ character varying(255),
    root_scope_type_ character varying(255),
    hierarchy_type_ character varying(255)
);


--
-- Name: act_hi_identitylink; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_identitylink (
    id_ character varying(64) NOT NULL,
    group_id_ character varying(255),
    type_ character varying(255),
    user_id_ character varying(255),
    task_id_ character varying(64),
    create_time_ timestamp(6) without time zone,
    proc_inst_id_ character varying(64),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255)
);


--
-- Name: act_hi_procinst; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_procinst (
    id_ character varying(64) NOT NULL,
    rev_ integer DEFAULT 1,
    proc_inst_id_ character varying(64) NOT NULL,
    business_key_ character varying(255),
    proc_def_id_ character varying(64) NOT NULL,
    start_time_ timestamp(6) without time zone NOT NULL,
    end_time_ timestamp(6) without time zone,
    duration_ bigint,
    start_user_id_ character varying(255),
    start_act_id_ character varying(255),
    end_act_id_ character varying(255),
    super_process_instance_id_ character varying(64),
    delete_reason_ character varying(4000),
    tenant_id_ character varying(255) DEFAULT ''::character varying,
    name_ character varying(255),
    callback_id_ character varying(255),
    callback_type_ character varying(255),
    reference_id_ character varying(255),
    reference_type_ character varying(255),
    propagated_stage_inst_id_ character varying(255),
    business_status_ character varying(255)
);


--
-- Name: act_hi_taskinst; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_taskinst (
    id_ character varying(64) NOT NULL,
    rev_ integer DEFAULT 1,
    proc_def_id_ character varying(64),
    task_def_id_ character varying(64),
    task_def_key_ character varying(255),
    proc_inst_id_ character varying(64),
    execution_id_ character varying(64),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    propagated_stage_inst_id_ character varying(255),
    state_ character varying(255),
    name_ character varying(255),
    parent_task_id_ character varying(64),
    description_ character varying(4000),
    owner_ character varying(255),
    assignee_ character varying(255),
    start_time_ timestamp(6) without time zone NOT NULL,
    in_progress_time_ timestamp(6) without time zone,
    in_progress_started_by_ character varying(255),
    claim_time_ timestamp(6) without time zone,
    claimed_by_ character varying(255),
    suspended_time_ timestamp(6) without time zone,
    suspended_by_ character varying(255),
    end_time_ timestamp(6) without time zone,
    completed_by_ character varying(255),
    duration_ bigint,
    delete_reason_ character varying(4000),
    priority_ integer,
    in_progress_due_date_ timestamp(6) without time zone,
    due_date_ timestamp(6) without time zone,
    form_key_ character varying(255),
    category_ character varying(255),
    tenant_id_ character varying(255) DEFAULT ''::character varying,
    last_updated_time_ timestamp(6) without time zone
);


--
-- Name: act_hi_tsk_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_tsk_log (
    id_ integer NOT NULL,
    type_ character varying(64),
    task_id_ character varying(64) NOT NULL,
    time_stamp_ timestamp(6) without time zone NOT NULL,
    user_id_ character varying(255),
    data_ character varying(4000),
    execution_id_ character varying(64),
    proc_inst_id_ character varying(64),
    proc_def_id_ character varying(64),
    scope_id_ character varying(255),
    scope_definition_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_hi_tsk_log_id__seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.act_hi_tsk_log_id__seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    MAXVALUE 2147483647
    CACHE 1;


--
-- Name: act_hi_tsk_log_id__seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.act_hi_tsk_log_id__seq OWNED BY public.act_hi_tsk_log.id_;


--
-- Name: act_hi_varinst; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_hi_varinst (
    id_ character varying(64) NOT NULL,
    rev_ integer DEFAULT 1,
    proc_inst_id_ character varying(64),
    execution_id_ character varying(64),
    task_id_ character varying(64),
    name_ character varying(255) NOT NULL,
    var_type_ character varying(100),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    bytearray_id_ character varying(64),
    double_ double precision,
    long_ bigint,
    text_ character varying(4000),
    text2_ character varying(4000),
    meta_info_ character varying(4000),
    create_time_ timestamp(6) without time zone,
    last_updated_time_ timestamp(6) without time zone
);


--
-- Name: act_id_bytearray; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_bytearray (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    name_ character varying(255),
    bytes_ bytea
);


--
-- Name: act_id_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_group (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    name_ character varying(255),
    type_ character varying(255)
);


--
-- Name: act_id_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_info (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    user_id_ character varying(64),
    type_ character varying(64),
    key_ character varying(255),
    value_ character varying(255),
    password_ bytea,
    parent_id_ character varying(255)
);


--
-- Name: act_id_membership; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_membership (
    user_id_ character varying(64) NOT NULL,
    group_id_ character varying(64) NOT NULL
);


--
-- Name: act_id_priv; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_priv (
    id_ character varying(64) NOT NULL,
    name_ character varying(255) NOT NULL
);


--
-- Name: act_id_priv_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_priv_mapping (
    id_ character varying(64) NOT NULL,
    priv_id_ character varying(64) NOT NULL,
    user_id_ character varying(255),
    group_id_ character varying(255)
);


--
-- Name: act_id_property; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_property (
    name_ character varying(64) NOT NULL,
    value_ character varying(300),
    rev_ integer
);


--
-- Name: act_id_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_token (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    token_value_ character varying(255),
    token_date_ timestamp(6) without time zone,
    ip_address_ character varying(255),
    user_agent_ character varying(255),
    user_id_ character varying(255),
    token_data_ character varying(2000)
);


--
-- Name: act_id_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_id_user (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    first_ character varying(255),
    last_ character varying(255),
    display_name_ character varying(255),
    email_ character varying(255),
    pwd_ character varying(255),
    picture_id_ character varying(64),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_procdef_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_procdef_info (
    id_ character varying(64) NOT NULL,
    proc_def_id_ character varying(64) NOT NULL,
    rev_ integer,
    info_json_id_ character varying(64)
);


--
-- Name: act_re_deployment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_re_deployment (
    id_ character varying(64) NOT NULL,
    name_ character varying(255),
    category_ character varying(255),
    key_ character varying(255),
    tenant_id_ character varying(255) DEFAULT ''::character varying,
    deploy_time_ timestamp(6) without time zone,
    derived_from_ character varying(64),
    derived_from_root_ character varying(64),
    parent_deployment_id_ character varying(255),
    engine_version_ character varying(255)
);


--
-- Name: act_re_model; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_re_model (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    name_ character varying(255),
    key_ character varying(255),
    category_ character varying(255),
    create_time_ timestamp(6) without time zone,
    last_update_time_ timestamp(6) without time zone,
    version_ integer,
    meta_info_ character varying(4000),
    deployment_id_ character varying(64),
    editor_source_value_id_ character varying(64),
    editor_source_extra_value_id_ character varying(64),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_re_procdef; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_re_procdef (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    category_ character varying(255),
    name_ character varying(255),
    key_ character varying(255) NOT NULL,
    version_ integer NOT NULL,
    deployment_id_ character varying(64),
    resource_name_ character varying(4000),
    dgrm_resource_name_ character varying(4000),
    description_ character varying(4000),
    has_start_form_key_ boolean,
    has_graphical_notation_ boolean,
    suspension_state_ integer,
    tenant_id_ character varying(255) DEFAULT ''::character varying,
    derived_from_ character varying(64),
    derived_from_root_ character varying(64),
    derived_version_ integer DEFAULT 0 NOT NULL,
    engine_version_ character varying(255)
);


--
-- Name: act_ru_actinst; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_actinst (
    id_ character varying(64) NOT NULL,
    rev_ integer DEFAULT 1,
    proc_def_id_ character varying(64) NOT NULL,
    proc_inst_id_ character varying(64) NOT NULL,
    execution_id_ character varying(64) NOT NULL,
    act_id_ character varying(255) NOT NULL,
    task_id_ character varying(64),
    call_proc_inst_id_ character varying(64),
    act_name_ character varying(255),
    act_type_ character varying(255) NOT NULL,
    assignee_ character varying(255),
    completed_by_ character varying(255),
    start_time_ timestamp(6) without time zone NOT NULL,
    end_time_ timestamp(6) without time zone,
    duration_ bigint,
    transaction_order_ integer,
    delete_reason_ character varying(4000),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_deadletter_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_deadletter_job (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    category_ character varying(255),
    type_ character varying(255) NOT NULL,
    exclusive_ boolean,
    execution_id_ character varying(64),
    process_instance_id_ character varying(64),
    proc_def_id_ character varying(64),
    element_id_ character varying(255),
    element_name_ character varying(255),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    correlation_id_ character varying(255),
    exception_stack_id_ character varying(64),
    exception_msg_ character varying(4000),
    duedate_ timestamp(6) without time zone,
    repeat_ character varying(255),
    handler_type_ character varying(255),
    handler_cfg_ character varying(4000),
    custom_values_id_ character varying(64),
    create_time_ timestamp(6) without time zone,
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_entitylink; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_entitylink (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    create_time_ timestamp(6) without time zone,
    link_type_ character varying(255),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    parent_element_id_ character varying(255),
    ref_scope_id_ character varying(255),
    ref_scope_type_ character varying(255),
    ref_scope_definition_id_ character varying(255),
    root_scope_id_ character varying(255),
    root_scope_type_ character varying(255),
    hierarchy_type_ character varying(255)
);


--
-- Name: act_ru_event_subscr; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_event_subscr (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    event_type_ character varying(255) NOT NULL,
    event_name_ character varying(255),
    execution_id_ character varying(64),
    proc_inst_id_ character varying(64),
    activity_id_ character varying(64),
    configuration_ character varying(255),
    created_ timestamp(6) without time zone NOT NULL,
    proc_def_id_ character varying(64),
    sub_scope_id_ character varying(64),
    scope_id_ character varying(64),
    scope_definition_id_ character varying(64),
    scope_definition_key_ character varying(255),
    scope_type_ character varying(64),
    lock_time_ timestamp(6) without time zone,
    lock_owner_ character varying(255),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_execution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_execution (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    proc_inst_id_ character varying(64),
    business_key_ character varying(255),
    parent_id_ character varying(64),
    proc_def_id_ character varying(64),
    super_exec_ character varying(64),
    root_proc_inst_id_ character varying(64),
    act_id_ character varying(255),
    is_active_ boolean,
    is_concurrent_ boolean,
    is_scope_ boolean,
    is_event_scope_ boolean,
    is_mi_root_ boolean,
    suspension_state_ integer,
    cached_ent_state_ integer,
    tenant_id_ character varying(255) DEFAULT ''::character varying,
    name_ character varying(255),
    start_act_id_ character varying(255),
    start_time_ timestamp(6) without time zone,
    start_user_id_ character varying(255),
    lock_time_ timestamp(6) without time zone,
    lock_owner_ character varying(255),
    is_count_enabled_ boolean,
    evt_subscr_count_ integer,
    task_count_ integer,
    job_count_ integer,
    timer_job_count_ integer,
    susp_job_count_ integer,
    deadletter_job_count_ integer,
    external_worker_job_count_ integer,
    var_count_ integer,
    id_link_count_ integer,
    callback_id_ character varying(255),
    callback_type_ character varying(255),
    reference_id_ character varying(255),
    reference_type_ character varying(255),
    propagated_stage_inst_id_ character varying(255),
    business_status_ character varying(255)
);


--
-- Name: act_ru_external_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_external_job (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    category_ character varying(255),
    type_ character varying(255) NOT NULL,
    lock_exp_time_ timestamp(6) without time zone,
    lock_owner_ character varying(255),
    exclusive_ boolean,
    execution_id_ character varying(64),
    process_instance_id_ character varying(64),
    proc_def_id_ character varying(64),
    element_id_ character varying(255),
    element_name_ character varying(255),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    correlation_id_ character varying(255),
    retries_ integer,
    exception_stack_id_ character varying(64),
    exception_msg_ character varying(4000),
    duedate_ timestamp(6) without time zone,
    repeat_ character varying(255),
    handler_type_ character varying(255),
    handler_cfg_ character varying(4000),
    custom_values_id_ character varying(64),
    create_time_ timestamp(6) without time zone,
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_history_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_history_job (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    lock_exp_time_ timestamp(6) without time zone,
    lock_owner_ character varying(255),
    retries_ integer,
    exception_stack_id_ character varying(64),
    exception_msg_ character varying(4000),
    handler_type_ character varying(255),
    handler_cfg_ character varying(4000),
    custom_values_id_ character varying(64),
    adv_handler_cfg_id_ character varying(64),
    create_time_ timestamp(6) without time zone,
    scope_type_ character varying(255),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_identitylink; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_identitylink (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    group_id_ character varying(255),
    type_ character varying(255),
    user_id_ character varying(255),
    task_id_ character varying(64),
    proc_inst_id_ character varying(64),
    proc_def_id_ character varying(64),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255)
);


--
-- Name: act_ru_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_job (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    category_ character varying(255),
    type_ character varying(255) NOT NULL,
    lock_exp_time_ timestamp(6) without time zone,
    lock_owner_ character varying(255),
    exclusive_ boolean,
    execution_id_ character varying(64),
    process_instance_id_ character varying(64),
    proc_def_id_ character varying(64),
    element_id_ character varying(255),
    element_name_ character varying(255),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    correlation_id_ character varying(255),
    retries_ integer,
    exception_stack_id_ character varying(64),
    exception_msg_ character varying(4000),
    duedate_ timestamp(6) without time zone,
    repeat_ character varying(255),
    handler_type_ character varying(255),
    handler_cfg_ character varying(4000),
    custom_values_id_ character varying(64),
    create_time_ timestamp(6) without time zone,
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_suspended_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_suspended_job (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    category_ character varying(255),
    type_ character varying(255) NOT NULL,
    exclusive_ boolean,
    execution_id_ character varying(64),
    process_instance_id_ character varying(64),
    proc_def_id_ character varying(64),
    element_id_ character varying(255),
    element_name_ character varying(255),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    correlation_id_ character varying(255),
    retries_ integer,
    exception_stack_id_ character varying(64),
    exception_msg_ character varying(4000),
    duedate_ timestamp(6) without time zone,
    repeat_ character varying(255),
    handler_type_ character varying(255),
    handler_cfg_ character varying(4000),
    custom_values_id_ character varying(64),
    create_time_ timestamp(6) without time zone,
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_task (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    execution_id_ character varying(64),
    proc_inst_id_ character varying(64),
    proc_def_id_ character varying(64),
    task_def_id_ character varying(64),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    propagated_stage_inst_id_ character varying(255),
    state_ character varying(255),
    name_ character varying(255),
    parent_task_id_ character varying(64),
    description_ character varying(4000),
    task_def_key_ character varying(255),
    owner_ character varying(255),
    assignee_ character varying(255),
    delegation_ character varying(64),
    priority_ integer,
    create_time_ timestamp(6) without time zone,
    in_progress_time_ timestamp(6) without time zone,
    in_progress_started_by_ character varying(255),
    claim_time_ timestamp(6) without time zone,
    claimed_by_ character varying(255),
    suspended_time_ timestamp(6) without time zone,
    suspended_by_ character varying(255),
    in_progress_due_date_ timestamp(6) without time zone,
    due_date_ timestamp(6) without time zone,
    category_ character varying(255),
    suspension_state_ integer,
    tenant_id_ character varying(255) DEFAULT ''::character varying,
    form_key_ character varying(255),
    is_count_enabled_ boolean,
    var_count_ integer,
    id_link_count_ integer,
    sub_task_count_ integer
);


--
-- Name: act_ru_timer_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_timer_job (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    category_ character varying(255),
    type_ character varying(255) NOT NULL,
    lock_exp_time_ timestamp(6) without time zone,
    lock_owner_ character varying(255),
    exclusive_ boolean,
    execution_id_ character varying(64),
    process_instance_id_ character varying(64),
    proc_def_id_ character varying(64),
    element_id_ character varying(255),
    element_name_ character varying(255),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    scope_definition_id_ character varying(255),
    correlation_id_ character varying(255),
    retries_ integer,
    exception_stack_id_ character varying(64),
    exception_msg_ character varying(4000),
    duedate_ timestamp(6) without time zone,
    repeat_ character varying(255),
    handler_type_ character varying(255),
    handler_cfg_ character varying(4000),
    custom_values_id_ character varying(64),
    create_time_ timestamp(6) without time zone,
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: act_ru_variable; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.act_ru_variable (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    type_ character varying(255) NOT NULL,
    name_ character varying(255) NOT NULL,
    execution_id_ character varying(64),
    proc_inst_id_ character varying(64),
    task_id_ character varying(64),
    scope_id_ character varying(255),
    sub_scope_id_ character varying(255),
    scope_type_ character varying(255),
    bytearray_id_ character varying(64),
    double_ double precision,
    long_ bigint,
    text_ character varying(4000),
    text2_ character varying(4000),
    meta_info_ character varying(4000)
);


--
-- Name: biz_famuuq8hfd_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_famuuq8hfd_report (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    name character varying(100) NOT NULL,
    CONSTRAINT biz_famuuq8hfd_report_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_famuuq8hfd_report; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_famuuq8hfd_report IS '表单验收报表加载';


--
-- Name: COLUMN biz_famuuq8hfd_report.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuq8hfd_report.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_famuuq8hfd_report.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuq8hfd_report.create_time IS '创建时间';


--
-- Name: COLUMN biz_famuuq8hfd_report.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuq8hfd_report.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_famuuq8hfd_report.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuq8hfd_report.update_time IS '更新时间';


--
-- Name: COLUMN biz_famuuq8hfd_report.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuq8hfd_report.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_famuuq8hfd_report.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuq8hfd_report.name IS '名称';


--
-- Name: biz_famuuq8hfd_report_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_famuuq8hfd_report ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_famuuq8hfd_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_famuuqabke_rulepivot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_famuuqabke_rulepivot (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    name character varying(100) NOT NULL,
    area character varying(100),
    department character varying(100),
    month character varying(100),
    amount numeric(20,4),
    computed numeric(20,4),
    CONSTRAINT biz_famuuqabke_rulepivot_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_famuuqabke_rulepivot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_famuuqabke_rulepivot IS '表单验收对象规则与透视合并验收';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.create_time IS '创建时间';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.update_time IS '更新时间';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.name IS '名称';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.area; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.area IS '区域';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.department; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.department IS '部门';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.month; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.month IS '月份';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.amount IS '金额';


--
-- Name: COLUMN biz_famuuqabke_rulepivot.computed; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqabke_rulepivot.computed IS '规则计算值';


--
-- Name: biz_famuuqabke_rulepivot_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_famuuqabke_rulepivot ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_famuuqabke_rulepivot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_famuuqom2g_legacyreport; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_famuuqom2g_legacyreport (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    name character varying(100) NOT NULL,
    company character varying(100),
    amount numeric(20,4),
    CONSTRAINT biz_famuuqom2g_legacyreport_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_famuuqom2g_legacyreport; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_famuuqom2g_legacyreport IS '表单验收旧报表兼容 famuuqom2g';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.create_time IS '创建时间';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.update_time IS '更新时间';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.name IS '名称';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.company; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.company IS '公司';


--
-- Name: COLUMN biz_famuuqom2g_legacyreport.amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_famuuqom2g_legacyreport.amount IS '金额';


--
-- Name: biz_famuuqom2g_legacyreport_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_famuuqom2g_legacyreport ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_famuuqom2g_legacyreport_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_cssjdx; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_cssjdx (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_csmc character varying(200) NOT NULL,
    c_yhls_id bigint NOT NULL,
    c_yhmc_id bigint NOT NULL,
    c_yhzh_id bigint,
    CONSTRAINT biz_object_cssjdx_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_cssjdx; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_cssjdx IS '测试数据对象';


--
-- Name: COLUMN biz_object_cssjdx.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_cssjdx.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_cssjdx.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_cssjdx.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_cssjdx.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_cssjdx.c_csmc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.c_csmc IS '测试名称';


--
-- Name: COLUMN biz_object_cssjdx.c_yhls_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.c_yhls_id IS '银行流水';


--
-- Name: COLUMN biz_object_cssjdx.c_yhmc_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.c_yhmc_id IS '银行名称';


--
-- Name: COLUMN biz_object_cssjdx.c_yhzh_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cssjdx.c_yhzh_id IS '银行账户';


--
-- Name: biz_object_cssjdx_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_cssjdx ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_cssjdx_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_cszdyrqx; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_cszdyrqx (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_mc character varying(200) NOT NULL,
    CONSTRAINT biz_object_cszdyrqx_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_cszdyrqx; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_cszdyrqx IS '测试自动引入权限';


--
-- Name: COLUMN biz_object_cszdyrqx.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cszdyrqx.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_cszdyrqx.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cszdyrqx.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_cszdyrqx.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cszdyrqx.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_cszdyrqx.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cszdyrqx.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_cszdyrqx.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cszdyrqx.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_cszdyrqx.c_mc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_cszdyrqx.c_mc IS '名称';


--
-- Name: biz_object_cszdyrqx_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_cszdyrqx ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_cszdyrqx_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_gs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_gs (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_zsmc character varying(200) NOT NULL,
    c_jc character varying(200),
    c_jm character varying(200),
    c_ywm character varying(200),
    c_frfh character varying(200),
    c_frdb character varying(200),
    c_cs1 character varying(200),
    c_cs2 character varying(200),
    c_gsfj jsonb,
    CONSTRAINT biz_object_gs_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_gs; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_gs IS '公司';


--
-- Name: COLUMN biz_object_gs.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_gs.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_gs.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_gs.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_gs.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_gs.c_zsmc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_zsmc IS '正式名称';


--
-- Name: COLUMN biz_object_gs.c_jc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_jc IS '简称';


--
-- Name: COLUMN biz_object_gs.c_jm; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_jm IS '假名';


--
-- Name: COLUMN biz_object_gs.c_ywm; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_ywm IS '英文名';


--
-- Name: COLUMN biz_object_gs.c_frfh; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_frfh IS '法人番号';


--
-- Name: COLUMN biz_object_gs.c_frdb; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_frdb IS '法人代表';


--
-- Name: COLUMN biz_object_gs.c_cs1; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_cs1 IS '测试 1';


--
-- Name: COLUMN biz_object_gs.c_cs2; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_cs2 IS '测试 2';


--
-- Name: COLUMN biz_object_gs.c_gsfj; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_gs.c_gsfj IS '公司附件';


--
-- Name: biz_object_gs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_gs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_gs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_kjkm; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_kjkm (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_kmbm character varying(200) NOT NULL,
    c_kmmc character varying(200),
    c_kjys character varying(100),
    c_zcye character varying(100),
    c_sx bigint,
    c_kjkmfl_id bigint,
    CONSTRAINT biz_object_kjkm_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_kjkm; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_kjkm IS '会计科目';


--
-- Name: COLUMN biz_object_kjkm.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_kjkm.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_kjkm.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_kjkm.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_kjkm.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_kjkm.c_kmbm; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.c_kmbm IS '科目编码';


--
-- Name: COLUMN biz_object_kjkm.c_kmmc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.c_kmmc IS '科目名称';


--
-- Name: COLUMN biz_object_kjkm.c_kjys; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.c_kjys IS '会计要素';


--
-- Name: COLUMN biz_object_kjkm.c_zcye; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.c_zcye IS '正常余额';


--
-- Name: COLUMN biz_object_kjkm.c_sx; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.c_sx IS '顺序';


--
-- Name: COLUMN biz_object_kjkm.c_kjkmfl_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkm.c_kjkmfl_id IS '会计科目分类';


--
-- Name: biz_object_kjkm_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_kjkm ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_kjkm_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_kjkmfl; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_kjkmfl (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_flbm character varying(200) NOT NULL,
    c_flmc character varying(200) NOT NULL,
    CONSTRAINT biz_object_kjkmfl_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_kjkmfl; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_kjkmfl IS '会计科目分类';


--
-- Name: COLUMN biz_object_kjkmfl.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkmfl.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_kjkmfl.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkmfl.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_kjkmfl.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkmfl.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_kjkmfl.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkmfl.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_kjkmfl.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkmfl.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_kjkmfl.c_flbm; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkmfl.c_flbm IS '分类编码';


--
-- Name: COLUMN biz_object_kjkmfl.c_flmc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjkmfl.c_flmc IS '分类名称';


--
-- Name: biz_object_kjkmfl_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_kjkmfl ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_kjkmfl_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_kjpzlr; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_kjpzlr (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_rq timestamp without time zone NOT NULL,
    c_nrmx character varying(200),
    c_rj numeric(18,2),
    c_cj numeric(18,2),
    c_yhzy character varying(200),
    c_bz character varying(200),
    c_gsmc character varying(200),
    c_kz character varying(200),
    c_zjls_id bigint,
    c_yhzh_id bigint,
    c_gs_id bigint,
    c_yhlsh character varying(200),
    c_pzzt character varying(100),
    CONSTRAINT biz_object_kjpzlr_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_kjpzlr; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_kjpzlr IS '会计凭证录入';


--
-- Name: COLUMN biz_object_kjpzlr.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_kjpzlr.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_kjpzlr.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_kjpzlr.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_kjpzlr.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_kjpzlr.c_rq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_rq IS '日期';


--
-- Name: COLUMN biz_object_kjpzlr.c_nrmx; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_nrmx IS '内容明细';


--
-- Name: COLUMN biz_object_kjpzlr.c_rj; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_rj IS '入金';


--
-- Name: COLUMN biz_object_kjpzlr.c_cj; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_cj IS '出金';


--
-- Name: COLUMN biz_object_kjpzlr.c_yhzy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_yhzy IS '银行摘要';


--
-- Name: COLUMN biz_object_kjpzlr.c_bz; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_bz IS '备注';


--
-- Name: COLUMN biz_object_kjpzlr.c_gsmc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_gsmc IS '公司名称';


--
-- Name: COLUMN biz_object_kjpzlr.c_kz; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_kz IS '口座';


--
-- Name: COLUMN biz_object_kjpzlr.c_zjls_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_zjls_id IS '资金流水';


--
-- Name: COLUMN biz_object_kjpzlr.c_yhzh_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_yhzh_id IS '银行账户';


--
-- Name: COLUMN biz_object_kjpzlr.c_gs_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_gs_id IS '公司';


--
-- Name: COLUMN biz_object_kjpzlr.c_yhlsh; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_yhlsh IS '银行流水号';


--
-- Name: COLUMN biz_object_kjpzlr.c_pzzt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_kjpzlr.c_pzzt IS '凭证状态';


--
-- Name: biz_object_kjpzlr_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_kjpzlr ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_kjpzlr_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_yhlb; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_yhlb (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_yhmc character varying(200) NOT NULL,
    CONSTRAINT biz_object_yhlb_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_yhlb; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_yhlb IS '银行列表';


--
-- Name: COLUMN biz_object_yhlb.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhlb.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_yhlb.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhlb.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_yhlb.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhlb.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_yhlb.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhlb.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_yhlb.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhlb.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_yhlb.c_yhmc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhlb.c_yhmc IS '银行名称';


--
-- Name: biz_object_yhlb_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_yhlb ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_yhlb_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_yhls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_yhls (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_nrmx character varying(200) NOT NULL,
    c_rq date,
    c_rj numeric(18,2),
    c_cj numeric(18,2),
    c_ye numeric(18,2),
    c_yhzy character varying(200),
    c_bz character varying(200),
    c_yhzh_id bigint,
    c_gsmc_id bigint,
    c_yhlsh character varying(200),
    c_djzt character varying(100),
    c_yex numeric(38,10),
    c_yhlshzd text,
    c_ye2 numeric(38,10),
    CONSTRAINT biz_object_yhls_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_yhls; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_yhls IS '银行流水';


--
-- Name: COLUMN biz_object_yhls.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_yhls.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_yhls.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_yhls.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_yhls.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_yhls.c_nrmx; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_nrmx IS '内容明细';


--
-- Name: COLUMN biz_object_yhls.c_rq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_rq IS '日期';


--
-- Name: COLUMN biz_object_yhls.c_rj; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_rj IS '入金';


--
-- Name: COLUMN biz_object_yhls.c_cj; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_cj IS '出金';


--
-- Name: COLUMN biz_object_yhls.c_ye; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_ye IS '余额';


--
-- Name: COLUMN biz_object_yhls.c_yhzy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_yhzy IS '银行摘要';


--
-- Name: COLUMN biz_object_yhls.c_bz; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_bz IS '备注';


--
-- Name: COLUMN biz_object_yhls.c_yhzh_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_yhzh_id IS '银行账户';


--
-- Name: COLUMN biz_object_yhls.c_gsmc_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_gsmc_id IS '公司名称';


--
-- Name: COLUMN biz_object_yhls.c_yhlsh; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_yhlsh IS '银行流水号';


--
-- Name: COLUMN biz_object_yhls.c_djzt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_djzt IS '登记状态';


--
-- Name: COLUMN biz_object_yhls.c_yex; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_yex IS '余额新';


--
-- Name: COLUMN biz_object_yhls.c_yhlshzd; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_yhlshzd IS '银行流水号（自动）';


--
-- Name: COLUMN biz_object_yhls.c_ye2; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhls.c_ye2 IS '余额 2';


--
-- Name: biz_object_yhls_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_yhls ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_yhls_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_object_yhzh; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_object_yhzh (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    c_zhmc character varying(200) NOT NULL,
    c_kzfh character varying(200),
    c_zdm character varying(200),
    c_yhmc_id bigint,
    c_gs_id bigint,
    CONSTRAINT biz_object_yhzh_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_object_yhzh; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_object_yhzh IS '银行账号';


--
-- Name: COLUMN biz_object_yhzh.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_object_yhzh.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.create_time IS '创建时间';


--
-- Name: COLUMN biz_object_yhzh.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_object_yhzh.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.update_time IS '更新时间';


--
-- Name: COLUMN biz_object_yhzh.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_object_yhzh.c_zhmc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.c_zhmc IS '账号名称';


--
-- Name: COLUMN biz_object_yhzh.c_kzfh; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.c_kzfh IS '口座番号';


--
-- Name: COLUMN biz_object_yhzh.c_zdm; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.c_zdm IS '支店名';


--
-- Name: COLUMN biz_object_yhzh.c_yhmc_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.c_yhmc_id IS '银行名称';


--
-- Name: COLUMN biz_object_yhzh.c_gs_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_object_yhzh.c_gs_id IS '公司';


--
-- Name: biz_object_yhzh_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_object_yhzh ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_object_yhzh_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_test_b1_211261a740c2457dorderedperf; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_test_b1_211261a740c2457dorderedperf (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    name character varying(100),
    account character varying(100),
    sequence bigint,
    incoming numeric(38,10),
    balance numeric(38,10),
    CONSTRAINT biz_test_b1_211261a740c2457dorderedperf_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_test_b1_211261a740c2457dorderedperf; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_test_b1_211261a740c2457dorderedperf IS '有序完整事务性能夹具';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.create_time IS '创建时间';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.update_time IS '更新时间';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.name IS '名称';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.account; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.account IS '分组';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.sequence; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.sequence IS '字段2';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.incoming; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.incoming IS '数值';


--
-- Name: COLUMN biz_test_b1_211261a740c2457dorderedperf.balance; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_211261a740c2457dorderedperf.balance IS '字段4';


--
-- Name: biz_test_b1_211261a740c2457dorderedperf_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_test_b1_211261a740c2457dorderedperf ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_test_b1_211261a740c2457dorderedperf_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_test_b1_4d33b085319540f3accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_test_b1_4d33b085319540f3accounts (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    name character varying(100),
    parent_id bigint,
    CONSTRAINT biz_test_b1_4d33b085319540f3accounts_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_test_b1_4d33b085319540f3accounts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_test_b1_4d33b085319540f3accounts IS 'test_b1_4d33b085319540f3来源账户';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3accounts.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3accounts.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3accounts.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3accounts.create_time IS '创建时间';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3accounts.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3accounts.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3accounts.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3accounts.update_time IS '更新时间';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3accounts.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3accounts.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3accounts.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3accounts.name IS '名称';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3accounts.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3accounts.parent_id IS 'test_b1_4d33b085319540f3来源公司';


--
-- Name: biz_test_b1_4d33b085319540f3accounts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_test_b1_4d33b085319540f3accounts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_test_b1_4d33b085319540f3accounts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_test_b1_4d33b085319540f3companies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_test_b1_4d33b085319540f3companies (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    name character varying(100),
    CONSTRAINT biz_test_b1_4d33b085319540f3companies_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_test_b1_4d33b085319540f3companies; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_test_b1_4d33b085319540f3companies IS 'test_b1_4d33b085319540f3来源公司';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3companies.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3companies.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3companies.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3companies.create_time IS '创建时间';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3companies.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3companies.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3companies.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3companies.update_time IS '更新时间';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3companies.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3companies.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3companies.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3companies.name IS '名称';


--
-- Name: biz_test_b1_4d33b085319540f3companies_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_test_b1_4d33b085319540f3companies ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_test_b1_4d33b085319540f3companies_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: biz_test_b1_4d33b085319540f3entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.biz_test_b1_4d33b085319540f3entries (
    id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying(64),
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT '0'::smallint NOT NULL,
    name character varying(100),
    amount numeric(20,2),
    occurred timestamp without time zone,
    business_day date,
    state character varying(100),
    checked boolean,
    parent_id bigint,
    CONSTRAINT biz_test_b1_4d33b085319540f3entries_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE biz_test_b1_4d33b085319540f3entries; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.biz_test_b1_4d33b085319540f3entries IS 'test_b1_4d33b085319540f3来源流水';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.create_time IS '创建时间';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.update_time IS '更新时间';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.name IS '名称';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.amount IS '金额';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.occurred; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.occurred IS '发生时间';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.business_day; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.business_day IS '业务日期';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.state IS '状态';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.checked; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.checked IS '已核销';


--
-- Name: COLUMN biz_test_b1_4d33b085319540f3entries.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.biz_test_b1_4d33b085319540f3entries.parent_id IS 'test_b1_4d33b085319540f3来源账户';


--
-- Name: biz_test_b1_4d33b085319540f3entries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.biz_test_b1_4d33b085319540f3entries ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.biz_test_b1_4d33b085319540f3entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: bpm_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_category (
    id bigint NOT NULL,
    name character varying(63) NOT NULL,
    code character varying(63) NOT NULL,
    description character varying(255) DEFAULT NULL::character varying,
    status integer NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE bpm_category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_category IS 'BPM流程分类';


--
-- Name: COLUMN bpm_category.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.id IS '分类编号';


--
-- Name: COLUMN bpm_category.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.name IS '分类名';


--
-- Name: COLUMN bpm_category.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.code IS '分类标志';


--
-- Name: COLUMN bpm_category.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.description IS '分类描述';


--
-- Name: COLUMN bpm_category.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.status IS '分类状态';


--
-- Name: COLUMN bpm_category.sort; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.sort IS '分类排序';


--
-- Name: COLUMN bpm_category.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.creator IS '创建者';


--
-- Name: COLUMN bpm_category.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.create_time IS '创建时间';


--
-- Name: COLUMN bpm_category.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.updater IS '更新者';


--
-- Name: COLUMN bpm_category.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_category.update_time IS '更新时间';


--
-- Name: bpm_form; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_form (
    id bigint NOT NULL,
    name character varying(63) NOT NULL,
    status integer NOT NULL,
    conf text NOT NULL,
    fields text NOT NULL,
    remark character varying(255) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE bpm_form; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_form IS 'BPM动态表单';


--
-- Name: COLUMN bpm_form.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.id IS '表单编号';


--
-- Name: COLUMN bpm_form.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.name IS '表单名';


--
-- Name: COLUMN bpm_form.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.status IS '表单状态';


--
-- Name: COLUMN bpm_form.conf; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.conf IS '表单配置JSON';


--
-- Name: COLUMN bpm_form.fields; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.fields IS '表单项JSON数组';


--
-- Name: COLUMN bpm_form.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.remark IS '备注';


--
-- Name: COLUMN bpm_form.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.creator IS '创建者';


--
-- Name: COLUMN bpm_form.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.create_time IS '创建时间';


--
-- Name: COLUMN bpm_form.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.updater IS '更新者';


--
-- Name: COLUMN bpm_form.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_form.update_time IS '更新时间';


--
-- Name: bpm_oa_leave; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_oa_leave (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    type integer NOT NULL,
    reason character varying(512) NOT NULL,
    start_time timestamp(6) without time zone NOT NULL,
    end_time timestamp(6) without time zone NOT NULL,
    day bigint NOT NULL,
    status integer NOT NULL,
    process_instance_id character varying(128) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted boolean DEFAULT false NOT NULL
);


--
-- Name: TABLE bpm_oa_leave; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_oa_leave IS 'BPM OA请假申请';


--
-- Name: COLUMN bpm_oa_leave.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.id IS '请假表单主键';


--
-- Name: COLUMN bpm_oa_leave.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.user_id IS '申请人用户编号';


--
-- Name: COLUMN bpm_oa_leave.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.type IS '请假类型';


--
-- Name: COLUMN bpm_oa_leave.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.reason IS '原因';


--
-- Name: COLUMN bpm_oa_leave.start_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.start_time IS '开始时间';


--
-- Name: COLUMN bpm_oa_leave.end_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.end_time IS '结束时间';


--
-- Name: COLUMN bpm_oa_leave.day; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.day IS '请假天数';


--
-- Name: COLUMN bpm_oa_leave.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.status IS '审批结果';


--
-- Name: COLUMN bpm_oa_leave.process_instance_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.process_instance_id IS '对应流程编号';


--
-- Name: COLUMN bpm_oa_leave.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.creator IS '创建者';


--
-- Name: COLUMN bpm_oa_leave.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.create_time IS '创建时间';


--
-- Name: COLUMN bpm_oa_leave.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.updater IS '更新者';


--
-- Name: COLUMN bpm_oa_leave.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.update_time IS '更新时间';


--
-- Name: COLUMN bpm_oa_leave.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_oa_leave.deleted IS '是否删除';


--
-- Name: bpm_process_definition_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_process_definition_info (
    id bigint NOT NULL,
    process_definition_id character varying(128) NOT NULL,
    model_id character varying(128) NOT NULL,
    model_type integer NOT NULL,
    category character varying(63) DEFAULT NULL::character varying,
    icon character varying(255) DEFAULT NULL::character varying,
    description character varying(512) DEFAULT NULL::character varying,
    form_type integer NOT NULL,
    form_id bigint,
    form_conf text,
    form_fields text,
    form_custom_create_path character varying(255) DEFAULT NULL::character varying,
    form_custom_view_path character varying(255) DEFAULT NULL::character varying,
    simple_model text,
    visible boolean DEFAULT true NOT NULL,
    sort bigint DEFAULT 0 NOT NULL,
    start_user_ids character varying(1024) DEFAULT NULL::character varying,
    start_dept_ids character varying(1024) DEFAULT NULL::character varying,
    manager_user_ids character varying(1024) DEFAULT NULL::character varying,
    allow_cancel_running_process boolean DEFAULT true NOT NULL,
    allow_withdraw_task boolean DEFAULT false NOT NULL,
    process_id_rule text,
    auto_approval_type integer,
    title_setting text,
    summary_setting text,
    process_before_trigger_setting text,
    process_after_trigger_setting text,
    task_before_trigger_setting text,
    task_after_trigger_setting text,
    print_template_setting text,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE bpm_process_definition_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_process_definition_info IS 'BPM流程定义扩展信息';


--
-- Name: COLUMN bpm_process_definition_info.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.id IS '编号';


--
-- Name: COLUMN bpm_process_definition_info.process_definition_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.process_definition_id IS '流程定义编号';


--
-- Name: COLUMN bpm_process_definition_info.model_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.model_id IS '流程模型编号';


--
-- Name: COLUMN bpm_process_definition_info.model_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.model_type IS '流程模型类型';


--
-- Name: COLUMN bpm_process_definition_info.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.category IS '流程分类编码';


--
-- Name: COLUMN bpm_process_definition_info.icon; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.icon IS '图标';


--
-- Name: COLUMN bpm_process_definition_info.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.description IS '描述';


--
-- Name: COLUMN bpm_process_definition_info.form_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.form_type IS '表单类型';


--
-- Name: COLUMN bpm_process_definition_info.form_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.form_id IS '动态表单编号';


--
-- Name: COLUMN bpm_process_definition_info.form_conf; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.form_conf IS '表单配置JSON';


--
-- Name: COLUMN bpm_process_definition_info.form_fields; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.form_fields IS '表单项JSON数组';


--
-- Name: COLUMN bpm_process_definition_info.form_custom_create_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.form_custom_create_path IS '自定义表单提交路径';


--
-- Name: COLUMN bpm_process_definition_info.form_custom_view_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.form_custom_view_path IS '自定义表单查看路径';


--
-- Name: COLUMN bpm_process_definition_info.simple_model; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.simple_model IS 'SIMPLE设计器模型JSON';


--
-- Name: COLUMN bpm_process_definition_info.visible; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.visible IS '是否可见';


--
-- Name: COLUMN bpm_process_definition_info.sort; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.sort IS '排序值';


--
-- Name: COLUMN bpm_process_definition_info.start_user_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.start_user_ids IS '可发起用户编号数组，逗号分隔';


--
-- Name: COLUMN bpm_process_definition_info.start_dept_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.start_dept_ids IS '可发起部门编号数组，逗号分隔';


--
-- Name: COLUMN bpm_process_definition_info.manager_user_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.manager_user_ids IS '可管理用户编号数组，逗号分隔';


--
-- Name: COLUMN bpm_process_definition_info.allow_cancel_running_process; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.allow_cancel_running_process IS '是否允许撤销审批中的申请';


--
-- Name: COLUMN bpm_process_definition_info.allow_withdraw_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.allow_withdraw_task IS '是否允许审批人撤回任务';


--
-- Name: COLUMN bpm_process_definition_info.process_id_rule; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.process_id_rule IS '流程ID规则JSON';


--
-- Name: COLUMN bpm_process_definition_info.auto_approval_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.auto_approval_type IS '自动去重类型';


--
-- Name: COLUMN bpm_process_definition_info.title_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.title_setting IS '标题设置JSON';


--
-- Name: COLUMN bpm_process_definition_info.summary_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.summary_setting IS '摘要设置JSON';


--
-- Name: COLUMN bpm_process_definition_info.process_before_trigger_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.process_before_trigger_setting IS '流程前置通知设置JSON';


--
-- Name: COLUMN bpm_process_definition_info.process_after_trigger_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.process_after_trigger_setting IS '流程后置通知设置JSON';


--
-- Name: COLUMN bpm_process_definition_info.task_before_trigger_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.task_before_trigger_setting IS '任务前置通知设置JSON';


--
-- Name: COLUMN bpm_process_definition_info.task_after_trigger_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.task_after_trigger_setting IS '任务后置通知设置JSON';


--
-- Name: COLUMN bpm_process_definition_info.print_template_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.print_template_setting IS '自定义打印模板设置JSON';


--
-- Name: COLUMN bpm_process_definition_info.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.creator IS '创建者';


--
-- Name: COLUMN bpm_process_definition_info.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.create_time IS '创建时间';


--
-- Name: COLUMN bpm_process_definition_info.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.updater IS '更新者';


--
-- Name: COLUMN bpm_process_definition_info.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_definition_info.update_time IS '更新时间';


--
-- Name: bpm_process_expression; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_process_expression (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    status integer NOT NULL,
    expression character varying(512) NOT NULL,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE bpm_process_expression; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_process_expression IS 'BPM流程表达式';


--
-- Name: COLUMN bpm_process_expression.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.id IS '编号';


--
-- Name: COLUMN bpm_process_expression.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.name IS '表达式名字';


--
-- Name: COLUMN bpm_process_expression.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.status IS '表达式状态';


--
-- Name: COLUMN bpm_process_expression.expression; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.expression IS '表达式';


--
-- Name: COLUMN bpm_process_expression.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.creator IS '创建者';


--
-- Name: COLUMN bpm_process_expression.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.create_time IS '创建时间';


--
-- Name: COLUMN bpm_process_expression.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.updater IS '更新者';


--
-- Name: COLUMN bpm_process_expression.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_expression.update_time IS '更新时间';


--
-- Name: bpm_process_instance_copy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_process_instance_copy (
    id bigint NOT NULL,
    start_user_id bigint NOT NULL,
    process_instance_name character varying(255) NOT NULL,
    process_instance_id character varying(128) NOT NULL,
    process_definition_id character varying(128) NOT NULL,
    category character varying(63) DEFAULT NULL::character varying,
    activity_id character varying(128) DEFAULT NULL::character varying,
    activity_name character varying(255) DEFAULT NULL::character varying,
    task_id character varying(128) DEFAULT NULL::character varying,
    user_id bigint NOT NULL,
    reason character varying(512) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint NOT NULL
);


--
-- Name: TABLE bpm_process_instance_copy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_process_instance_copy IS 'BPM流程抄送';


--
-- Name: COLUMN bpm_process_instance_copy.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.id IS '编号';


--
-- Name: COLUMN bpm_process_instance_copy.start_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.start_user_id IS '发起人ID';


--
-- Name: COLUMN bpm_process_instance_copy.process_instance_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.process_instance_name IS '流程名';


--
-- Name: COLUMN bpm_process_instance_copy.process_instance_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.process_instance_id IS '流程实例编号';


--
-- Name: COLUMN bpm_process_instance_copy.process_definition_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.process_definition_id IS '流程定义编号';


--
-- Name: COLUMN bpm_process_instance_copy.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.category IS '流程分类';


--
-- Name: COLUMN bpm_process_instance_copy.activity_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.activity_id IS '流程活动编号';


--
-- Name: COLUMN bpm_process_instance_copy.activity_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.activity_name IS '流程活动名字';


--
-- Name: COLUMN bpm_process_instance_copy.task_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.task_id IS '历史任务编号';


--
-- Name: COLUMN bpm_process_instance_copy.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.user_id IS '被抄送用户编号';


--
-- Name: COLUMN bpm_process_instance_copy.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.reason IS '抄送意见';


--
-- Name: COLUMN bpm_process_instance_copy.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.creator IS '创建者';


--
-- Name: COLUMN bpm_process_instance_copy.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.create_time IS '创建时间';


--
-- Name: COLUMN bpm_process_instance_copy.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.updater IS '更新者';


--
-- Name: COLUMN bpm_process_instance_copy.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_instance_copy.update_time IS '更新时间';


--
-- Name: bpm_process_listener; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_process_listener (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    status integer NOT NULL,
    type character varying(32) NOT NULL,
    event character varying(32) NOT NULL,
    value_type character varying(32) NOT NULL,
    value character varying(512) NOT NULL,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint NOT NULL
);


--
-- Name: TABLE bpm_process_listener; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_process_listener IS 'BPM流程监听器模板';


--
-- Name: COLUMN bpm_process_listener.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.id IS '主键ID';


--
-- Name: COLUMN bpm_process_listener.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.name IS '监听器名字';


--
-- Name: COLUMN bpm_process_listener.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.status IS '状态';


--
-- Name: COLUMN bpm_process_listener.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.type IS '监听类型';


--
-- Name: COLUMN bpm_process_listener.event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.event IS '监听事件';


--
-- Name: COLUMN bpm_process_listener.value_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.value_type IS '值类型';


--
-- Name: COLUMN bpm_process_listener.value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.value IS '监听器值';


--
-- Name: COLUMN bpm_process_listener.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.creator IS '创建者';


--
-- Name: COLUMN bpm_process_listener.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.create_time IS '创建时间';


--
-- Name: COLUMN bpm_process_listener.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.updater IS '更新者';


--
-- Name: COLUMN bpm_process_listener.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_process_listener.update_time IS '更新时间';


--
-- Name: bpm_user_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bpm_user_group (
    id bigint NOT NULL,
    name character varying(63) NOT NULL,
    description character varying(255) DEFAULT NULL::character varying,
    status integer NOT NULL,
    user_ids text NOT NULL,
    creator character varying(64) DEFAULT NULL::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT NULL::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE bpm_user_group; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bpm_user_group IS 'BPM用户组';


--
-- Name: COLUMN bpm_user_group.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.id IS '编号';


--
-- Name: COLUMN bpm_user_group.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.name IS '组名';


--
-- Name: COLUMN bpm_user_group.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.description IS '描述';


--
-- Name: COLUMN bpm_user_group.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.status IS '状态';


--
-- Name: COLUMN bpm_user_group.user_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.user_ids IS '成员用户编号JSON数组';


--
-- Name: COLUMN bpm_user_group.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.creator IS '创建者';


--
-- Name: COLUMN bpm_user_group.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.create_time IS '创建时间';


--
-- Name: COLUMN bpm_user_group.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.updater IS '更新者';


--
-- Name: COLUMN bpm_user_group.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bpm_user_group.update_time IS '更新时间';


--
-- Name: drive_entry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_entry (
    id bigint NOT NULL,
    space_id bigint NOT NULL,
    parent_id bigint DEFAULT 0 NOT NULL,
    name character varying(255) NOT NULL,
    type character varying(16) NOT NULL,
    file_id bigint,
    size bigint DEFAULT 0 NOT NULL,
    mime_type character varying(128),
    inherit_parent boolean DEFAULT true NOT NULL,
    trash_state character varying(16) DEFAULT 'NORMAL'::character varying NOT NULL,
    trashed_at timestamp(6) without time zone,
    trashed_by character varying(64),
    origin_parent_id bigint,
    lock_version integer DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    managed_biz boolean DEFAULT false NOT NULL,
    CONSTRAINT drive_entry_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE drive_entry; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_entry IS '网盘逻辑节点：目录与文件节点；文件内容在 infra_file，节点只保存展示名称与授权位置';


--
-- Name: COLUMN drive_entry.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.parent_id IS '父节点编号，0 表示空间根目录';


--
-- Name: COLUMN drive_entry.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.type IS '节点类型：FOLDER 目录、FILE 文件';


--
-- Name: COLUMN drive_entry.file_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.file_id IS '文件节点的内容编号，关联 infra_file.id；目录为空';


--
-- Name: COLUMN drive_entry.inherit_parent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.inherit_parent IS '是否继承上级授权：false 时该节点及其子树忽略祖先授权，仅按本级授权判定';


--
-- Name: COLUMN drive_entry.trash_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.trash_state IS '回收站状态：NORMAL 正常、TRASHED 已移入回收站；回收站内容保留内容记录，只有彻底删除才清理存储';


--
-- Name: COLUMN drive_entry.origin_parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.origin_parent_id IS '移入回收站前的父节点，用于恢复时回到原位置';


--
-- Name: COLUMN drive_entry.lock_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.lock_version IS '乐观锁版本，防止移动与重命名并发写坏目录结构';


--
-- Name: COLUMN drive_entry.managed_biz; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry.managed_biz IS '是否为业务受管节点：由无代码业务规则建立与调整，普通网盘操作入口拒绝修改';


--
-- Name: drive_entry_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.drive_entry ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.drive_entry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: drive_entry_origin; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_entry_origin (
    entry_id bigint NOT NULL,
    origin_key character varying(700) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT drive_entry_origin_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE drive_entry_origin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_entry_origin IS '网盘节点来源标记：节点经由哪个业务来源放入；对网盘是不透明字符串。没有有效行（deleted = 0）= 没有来源（直接在网盘里放的）';


--
-- Name: COLUMN drive_entry_origin.origin_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_entry_origin.origin_key IS '来源键；无代码侧写入「对象编号:记录编号」';


--
-- Name: drive_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_permission (
    id bigint NOT NULL,
    space_id bigint NOT NULL,
    entry_id bigint DEFAULT 0 NOT NULL,
    subject_type character varying(16) NOT NULL,
    subject_id bigint NOT NULL,
    role character varying(16) NOT NULL,
    include_children boolean DEFAULT true NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT drive_permission_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE drive_permission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_permission IS '网盘授权：授权到空间或目录节点，对该节点及其子树生效；继承边界由被授权节点的 inherit_parent 决定';


--
-- Name: COLUMN drive_permission.entry_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_permission.entry_id IS '被授权的节点编号，0 表示整个空间';


--
-- Name: COLUMN drive_permission.subject_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_permission.subject_type IS '授权主体类型：USER 用户、DEPT 部门';


--
-- Name: COLUMN drive_permission.subject_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_permission.subject_id IS '授权主体编号：用户编号或部门编号';


--
-- Name: COLUMN drive_permission.role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_permission.role IS '授权角色：VIEWER 可查看、EDITOR 可编辑、MANAGER 可管理（含再授权）';


--
-- Name: COLUMN drive_permission.include_children; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_permission.include_children IS '部门授权是否含下级部门：true 时主体所在部门为该授权部门的子孙部门也算命中；用户授权忽略该列';


--
-- Name: drive_permission_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.drive_permission ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.drive_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: drive_share; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_share (
    id bigint NOT NULL,
    space_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    role character varying(16) NOT NULL,
    expire_time timestamp(6) without time zone,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT drive_share_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE drive_share; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_share IS '网盘组织内分享：一条分享对应一个节点与一组接收主体，到期或撤销后不再授予访问';


--
-- Name: COLUMN drive_share.expire_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_share.expire_time IS '分享失效时间，为空表示长期有效';


--
-- Name: COLUMN drive_share.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_share.status IS '分享状态：ACTIVE 生效、REVOKED 已撤销';


--
-- Name: drive_share_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.drive_share ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.drive_share_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: drive_share_subject; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_share_subject (
    id bigint NOT NULL,
    share_id bigint NOT NULL,
    subject_type character varying(16) NOT NULL,
    subject_id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT drive_share_subject_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE drive_share_subject; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_share_subject IS '网盘分享接收主体：与分享一对一展开，支持用户与部门';


--
-- Name: COLUMN drive_share_subject.subject_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_share_subject.subject_type IS '主体类型：USER 用户、DEPT 部门';


--
-- Name: COLUMN drive_share_subject.subject_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_share_subject.subject_id IS '主体编号：用户编号或部门编号';


--
-- Name: drive_share_subject_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.drive_share_subject ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.drive_share_subject_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: drive_space; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_space (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    type character varying(16) NOT NULL,
    owner_id bigint,
    owner_dept_id bigint,
    quota_bytes bigint DEFAULT 0 NOT NULL,
    used_bytes bigint DEFAULT 0 NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT drive_space_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE drive_space; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_space IS '网盘空间：个人空间按用户唯一建立，团队空间按部门或责任人建立';


--
-- Name: COLUMN drive_space.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_space.type IS '空间类型：PERSONAL 个人空间、TEAM 团队空间';


--
-- Name: COLUMN drive_space.owner_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_space.owner_id IS '归属主体用户编号：个人空间必填，团队空间为责任人，业务空间为空';


--
-- Name: COLUMN drive_space.owner_dept_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_space.owner_dept_id IS '归属部门编号：团队空间所属部门，个人空间为空';


--
-- Name: COLUMN drive_space.quota_bytes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_space.quota_bytes IS '容量配额（字节），0 表示不限制；V1 只记录不拦截上传';


--
-- Name: COLUMN drive_space.used_bytes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_space.used_bytes IS '已用容量（字节），按未进回收站的文件节点累计，回收站内容不占容量';


--
-- Name: COLUMN drive_space.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_space.status IS '空间状态，枚举 CommonStatusEnum：0 开启、1 关闭；关闭后不再提供内容访问';


--
-- Name: drive_space_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.drive_space ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.drive_space_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: drive_storage_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_storage_setting (
    id bigint NOT NULL,
    config_id bigint,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT drive_storage_setting_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT drive_storage_setting_id_check CHECK ((id = 1))
);


--
-- Name: TABLE drive_storage_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_storage_setting IS '网盘后续写入使用的文件配置；空值沿用平台主配置';


--
-- Name: COLUMN drive_storage_setting.config_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_storage_setting.config_id IS '引用 infra_file_config.id；旧文件仍使用各自 infra_file.config_id';


--
-- Name: drive_user_mark; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drive_user_mark (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    mark_type character varying(16) NOT NULL,
    access_time timestamp(6) without time zone,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT drive_user_mark_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE drive_user_mark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.drive_user_mark IS '网盘用户标记：收藏与最近访问，按用户独立，不改变节点授权';


--
-- Name: COLUMN drive_user_mark.mark_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_user_mark.mark_type IS '标记类型：FAVORITE 收藏、RECENT 最近访问';


--
-- Name: COLUMN drive_user_mark.access_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.drive_user_mark.access_time IS '最近访问时间，仅 RECENT 使用';


--
-- Name: drive_user_mark_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.drive_user_mark ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.drive_user_mark_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: dual; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dual (
    id smallint
);


--
-- Name: TABLE dual; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.dual IS '数据库连接的表';


--
-- Name: flw_channel_definition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flw_channel_definition (
    id_ character varying(255) NOT NULL,
    name_ character varying(255),
    version_ integer,
    key_ character varying(255),
    category_ character varying(255),
    type_ character varying(255),
    implementation_ character varying(255),
    deployment_id_ character varying(255),
    create_time_ timestamp(3) without time zone,
    tenant_id_ character varying(255),
    resource_name_ character varying(255),
    description_ character varying(255)
);


--
-- Name: flw_event_definition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flw_event_definition (
    id_ character varying(255) NOT NULL,
    name_ character varying(255),
    version_ integer,
    key_ character varying(255),
    category_ character varying(255),
    deployment_id_ character varying(255),
    tenant_id_ character varying(255),
    resource_name_ character varying(255),
    description_ character varying(255)
);


--
-- Name: flw_event_deployment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flw_event_deployment (
    id_ character varying(255) NOT NULL,
    name_ character varying(255),
    category_ character varying(255),
    deploy_time_ timestamp(3) without time zone,
    tenant_id_ character varying(255),
    parent_deployment_id_ character varying(255)
);


--
-- Name: flw_event_resource; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flw_event_resource (
    id_ character varying(255) NOT NULL,
    name_ character varying(255),
    deployment_id_ character varying(255),
    resource_bytes_ bytea
);


--
-- Name: flw_ru_batch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flw_ru_batch (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    type_ character varying(64) NOT NULL,
    search_key_ character varying(255),
    search_key2_ character varying(255),
    create_time_ timestamp(6) without time zone NOT NULL,
    complete_time_ timestamp(6) without time zone,
    status_ character varying(255),
    batch_doc_id_ character varying(64),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: flw_ru_batch_part; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flw_ru_batch_part (
    id_ character varying(64) NOT NULL,
    rev_ integer,
    batch_id_ character varying(64),
    type_ character varying(64) NOT NULL,
    scope_id_ character varying(64),
    sub_scope_id_ character varying(64),
    scope_type_ character varying(64),
    search_key_ character varying(255),
    search_key2_ character varying(255),
    create_time_ timestamp(6) without time zone NOT NULL,
    complete_time_ timestamp(6) without time zone,
    status_ character varying(255),
    result_doc_id_ character varying(64),
    tenant_id_ character varying(255) DEFAULT ''::character varying
);


--
-- Name: generation_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.generation_record (
    id bigint NOT NULL,
    task_id character varying(64) NOT NULL,
    user_id character varying(100) NOT NULL,
    scaffold_id bigint NOT NULL,
    scaffold_name character varying(200) NOT NULL,
    scaffold_version_id bigint NOT NULL,
    scaffold_version_tag character varying(50) NOT NULL,
    selected_components jsonb DEFAULT '[]'::jsonb NOT NULL,
    project_info jsonb NOT NULL,
    config_values jsonb DEFAULT '{}'::jsonb NOT NULL,
    result_zip_path character varying(500),
    delivery_methods jsonb DEFAULT '[]'::jsonb NOT NULL,
    delivery_results jsonb DEFAULT '[]'::jsonb NOT NULL,
    create_time timestamp(6) without time zone,
    creator character varying(32),
    update_time timestamp(6) without time zone,
    updater character varying(32),
    deleted smallint DEFAULT 0 NOT NULL,
    sub_projects jsonb,
    git_config jsonb
);


--
-- Name: TABLE generation_record; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.generation_record IS '生成记录表，保存每次项目生成的完整快照';


--
-- Name: COLUMN generation_record.task_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.task_id IS '关联 generation_task.task_id';


--
-- Name: COLUMN generation_record.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.user_id IS '生成者用户ID';


--
-- Name: COLUMN generation_record.scaffold_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.scaffold_id IS '使用的底板ID，关联 scaffold_template.id';


--
-- Name: COLUMN generation_record.scaffold_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.scaffold_name IS '底板名称快照';


--
-- Name: COLUMN generation_record.scaffold_version_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.scaffold_version_id IS '使用的底板版本ID，关联 scaffold_version.id';


--
-- Name: COLUMN generation_record.scaffold_version_tag; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.scaffold_version_tag IS '版本号快照';


--
-- Name: COLUMN generation_record.selected_components; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.selected_components IS '选中组件快照JSONB数组，结构包含key/display_name/version_tag/target_sub_project/snippet';


--
-- Name: COLUMN generation_record.project_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.project_info IS '项目信息JSONB，按子工程名为key，值为各语言的项目配置';


--
-- Name: COLUMN generation_record.config_values; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.config_values IS '配置值JSONB，key-value格式';


--
-- Name: COLUMN generation_record.result_zip_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.result_zip_path IS '生成产物Zip文件MinIO路径，30天有效';


--
-- Name: COLUMN generation_record.delivery_methods; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.delivery_methods IS '交付方式JSONB数组，如[{type:zip_download, enabled:true},{type:git_push, enabled:true}]';


--
-- Name: COLUMN generation_record.delivery_results; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.delivery_results IS '交付结果JSONB数组，包含各交付方式的状态和详情';


--
-- Name: COLUMN generation_record.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.creator IS '创建人ID（生成者）';


--
-- Name: COLUMN generation_record.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.updater IS '更新人ID';


--
-- Name: COLUMN generation_record.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_record.deleted IS '逻辑删除：0-未删除 1-已删除';


--
-- Name: generation_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.generation_task (
    id bigint NOT NULL,
    task_id character varying(64) NOT NULL,
    status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    request_params jsonb NOT NULL,
    stage_detail jsonb,
    error_message text,
    expires_at timestamp(6) without time zone NOT NULL,
    create_time timestamp(6) without time zone,
    creator character varying(32),
    update_time timestamp(6) without time zone,
    updater character varying(32),
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE generation_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.generation_task IS '异步生成任务表，追踪5阶段生成过程';


--
-- Name: COLUMN generation_task.task_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.task_id IS '任务唯一标识（gen-{timestamp}）';


--
-- Name: COLUMN generation_task.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.status IS '任务状态：pending/validating/fetching/injecting/delivering/completed/failed/timeout';


--
-- Name: COLUMN generation_task.request_params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.request_params IS '用户提交的完整生成参数JSONB快照';


--
-- Name: COLUMN generation_task.stage_detail; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.stage_detail IS '各阶段执行详情JSONB，用于WebSocket进度推送';


--
-- Name: COLUMN generation_task.error_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.error_message IS '失败原因';


--
-- Name: COLUMN generation_task.expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.expires_at IS '任务超时时间（创建后+10min）';


--
-- Name: COLUMN generation_task.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.creator IS '创建人ID（任务发起者）';


--
-- Name: COLUMN generation_task.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.updater IS '更新人ID';


--
-- Name: COLUMN generation_task.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.generation_task.deleted IS '逻辑删除：0-未删除 1-已删除';


--
-- Name: infra_api_access_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_api_access_log (
    id bigint NOT NULL,
    trace_id character varying(64) DEFAULT ''::character varying NOT NULL,
    user_id bigint DEFAULT 0 NOT NULL,
    user_type smallint DEFAULT 0 NOT NULL,
    application_name character varying(50) NOT NULL,
    request_method character varying(16) DEFAULT ''::character varying NOT NULL,
    request_url character varying(255) DEFAULT ''::character varying NOT NULL,
    request_params text,
    response_body text,
    user_ip character varying(50) NOT NULL,
    user_agent character varying(512) NOT NULL,
    operate_module character varying(50) DEFAULT NULL::character varying,
    operate_name character varying(50) DEFAULT NULL::character varying,
    operate_type smallint DEFAULT 0,
    begin_time timestamp(6) without time zone NOT NULL,
    end_time timestamp(6) without time zone NOT NULL,
    duration integer NOT NULL,
    result_code integer DEFAULT 0 NOT NULL,
    result_msg character varying(512) DEFAULT ''::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_api_access_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_api_access_log IS 'API 访问日志表';


--
-- Name: COLUMN infra_api_access_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.id IS '日志主键';


--
-- Name: COLUMN infra_api_access_log.trace_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.trace_id IS '链路追踪编号';


--
-- Name: COLUMN infra_api_access_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.user_id IS '用户编号';


--
-- Name: COLUMN infra_api_access_log.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.user_type IS '用户类型';


--
-- Name: COLUMN infra_api_access_log.application_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.application_name IS '应用名';


--
-- Name: COLUMN infra_api_access_log.request_method; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.request_method IS '请求方法名';


--
-- Name: COLUMN infra_api_access_log.request_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.request_url IS '请求地址';


--
-- Name: COLUMN infra_api_access_log.request_params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.request_params IS '请求参数';


--
-- Name: COLUMN infra_api_access_log.response_body; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.response_body IS '响应结果';


--
-- Name: COLUMN infra_api_access_log.user_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.user_ip IS '用户 IP';


--
-- Name: COLUMN infra_api_access_log.user_agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.user_agent IS '浏览器 UA';


--
-- Name: COLUMN infra_api_access_log.operate_module; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.operate_module IS '操作模块';


--
-- Name: COLUMN infra_api_access_log.operate_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.operate_name IS '操作名';


--
-- Name: COLUMN infra_api_access_log.operate_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.operate_type IS '操作分类';


--
-- Name: COLUMN infra_api_access_log.begin_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.begin_time IS '开始请求时间';


--
-- Name: COLUMN infra_api_access_log.end_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.end_time IS '结束请求时间';


--
-- Name: COLUMN infra_api_access_log.duration; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.duration IS '执行时长';


--
-- Name: COLUMN infra_api_access_log.result_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.result_code IS '结果码';


--
-- Name: COLUMN infra_api_access_log.result_msg; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.result_msg IS '结果提示';


--
-- Name: COLUMN infra_api_access_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.creator IS '创建者';


--
-- Name: COLUMN infra_api_access_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.create_time IS '创建时间';


--
-- Name: COLUMN infra_api_access_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.updater IS '更新者';


--
-- Name: COLUMN infra_api_access_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.update_time IS '更新时间';


--
-- Name: COLUMN infra_api_access_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.deleted IS '是否删除';


--
-- Name: COLUMN infra_api_access_log.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_access_log.tenant_id IS '租户编号';


--
-- Name: infra_api_access_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_api_access_log_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_api_error_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_api_error_log (
    id bigint NOT NULL,
    trace_id character varying(64) NOT NULL,
    user_id bigint DEFAULT 0 NOT NULL,
    user_type smallint DEFAULT 0 NOT NULL,
    application_name character varying(50) NOT NULL,
    request_method character varying(16) NOT NULL,
    request_url character varying(255) NOT NULL,
    request_params character varying(8000) NOT NULL,
    user_ip character varying(50) NOT NULL,
    user_agent character varying(512) NOT NULL,
    exception_time timestamp(6) without time zone NOT NULL,
    exception_name character varying(128) DEFAULT ''::character varying NOT NULL,
    exception_message text NOT NULL,
    exception_root_cause_message text NOT NULL,
    exception_stack_trace text NOT NULL,
    exception_class_name character varying(512) NOT NULL,
    exception_file_name character varying(512) NOT NULL,
    exception_method_name character varying(512) NOT NULL,
    exception_line_number integer NOT NULL,
    process_status smallint NOT NULL,
    process_time timestamp(6) without time zone,
    process_user_id integer DEFAULT 0,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_api_error_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_api_error_log IS '系统异常日志';


--
-- Name: COLUMN infra_api_error_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.id IS '编号';


--
-- Name: COLUMN infra_api_error_log.trace_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.trace_id IS '链路追踪编号';


--
-- Name: COLUMN infra_api_error_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.user_id IS '用户编号';


--
-- Name: COLUMN infra_api_error_log.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.user_type IS '用户类型';


--
-- Name: COLUMN infra_api_error_log.application_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.application_name IS '应用名';


--
-- Name: COLUMN infra_api_error_log.request_method; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.request_method IS '请求方法名';


--
-- Name: COLUMN infra_api_error_log.request_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.request_url IS '请求地址';


--
-- Name: COLUMN infra_api_error_log.request_params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.request_params IS '请求参数';


--
-- Name: COLUMN infra_api_error_log.user_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.user_ip IS '用户 IP';


--
-- Name: COLUMN infra_api_error_log.user_agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.user_agent IS '浏览器 UA';


--
-- Name: COLUMN infra_api_error_log.exception_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_time IS '异常发生时间';


--
-- Name: COLUMN infra_api_error_log.exception_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_name IS '异常名';


--
-- Name: COLUMN infra_api_error_log.exception_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_message IS '异常导致的消息';


--
-- Name: COLUMN infra_api_error_log.exception_root_cause_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_root_cause_message IS '异常导致的根消息';


--
-- Name: COLUMN infra_api_error_log.exception_stack_trace; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_stack_trace IS '异常的栈轨迹';


--
-- Name: COLUMN infra_api_error_log.exception_class_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_class_name IS '异常发生的类全名';


--
-- Name: COLUMN infra_api_error_log.exception_file_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_file_name IS '异常发生的类文件';


--
-- Name: COLUMN infra_api_error_log.exception_method_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_method_name IS '异常发生的方法名';


--
-- Name: COLUMN infra_api_error_log.exception_line_number; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.exception_line_number IS '异常发生的方法所在行';


--
-- Name: COLUMN infra_api_error_log.process_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.process_status IS '处理状态';


--
-- Name: COLUMN infra_api_error_log.process_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.process_time IS '处理时间';


--
-- Name: COLUMN infra_api_error_log.process_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.process_user_id IS '处理用户编号';


--
-- Name: COLUMN infra_api_error_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.creator IS '创建者';


--
-- Name: COLUMN infra_api_error_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.create_time IS '创建时间';


--
-- Name: COLUMN infra_api_error_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.updater IS '更新者';


--
-- Name: COLUMN infra_api_error_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.update_time IS '更新时间';


--
-- Name: COLUMN infra_api_error_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.deleted IS '是否删除';


--
-- Name: COLUMN infra_api_error_log.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_api_error_log.tenant_id IS '租户编号';


--
-- Name: infra_api_error_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_api_error_log_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_codegen_column; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_codegen_column (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    column_name character varying(200) NOT NULL,
    data_type character varying(100) NOT NULL,
    column_comment character varying(500) NOT NULL,
    nullable boolean NOT NULL,
    primary_key boolean NOT NULL,
    ordinal_position integer NOT NULL,
    java_type character varying(32) NOT NULL,
    java_field character varying(64) NOT NULL,
    dict_type character varying(200) DEFAULT ''::character varying,
    example character varying(64) DEFAULT NULL::character varying,
    create_operation boolean NOT NULL,
    update_operation boolean NOT NULL,
    list_operation boolean NOT NULL,
    list_operation_condition character varying(32) DEFAULT '='::character varying NOT NULL,
    list_operation_result boolean NOT NULL,
    html_type character varying(32) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_codegen_column; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_codegen_column IS '代码生成表字段定义';


--
-- Name: COLUMN infra_codegen_column.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.id IS '编号';


--
-- Name: COLUMN infra_codegen_column.table_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.table_id IS '表编号';


--
-- Name: COLUMN infra_codegen_column.column_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.column_name IS '字段名';


--
-- Name: COLUMN infra_codegen_column.data_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.data_type IS '字段类型';


--
-- Name: COLUMN infra_codegen_column.column_comment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.column_comment IS '字段描述';


--
-- Name: COLUMN infra_codegen_column.nullable; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.nullable IS '是否允许为空';


--
-- Name: COLUMN infra_codegen_column.primary_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.primary_key IS '是否主键';


--
-- Name: COLUMN infra_codegen_column.ordinal_position; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.ordinal_position IS '排序';


--
-- Name: COLUMN infra_codegen_column.java_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.java_type IS 'Java 属性类型';


--
-- Name: COLUMN infra_codegen_column.java_field; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.java_field IS 'Java 属性名';


--
-- Name: COLUMN infra_codegen_column.dict_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.dict_type IS '字典类型';


--
-- Name: COLUMN infra_codegen_column.example; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.example IS '数据示例';


--
-- Name: COLUMN infra_codegen_column.create_operation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.create_operation IS '是否为 Create 创建操作的字段';


--
-- Name: COLUMN infra_codegen_column.update_operation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.update_operation IS '是否为 Update 更新操作的字段';


--
-- Name: COLUMN infra_codegen_column.list_operation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.list_operation IS '是否为 List 查询操作的字段';


--
-- Name: COLUMN infra_codegen_column.list_operation_condition; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.list_operation_condition IS 'List 查询操作的条件类型';


--
-- Name: COLUMN infra_codegen_column.list_operation_result; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.list_operation_result IS '是否为 List 查询操作的返回字段';


--
-- Name: COLUMN infra_codegen_column.html_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.html_type IS '显示类型';


--
-- Name: COLUMN infra_codegen_column.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.creator IS '创建者';


--
-- Name: COLUMN infra_codegen_column.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.create_time IS '创建时间';


--
-- Name: COLUMN infra_codegen_column.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.updater IS '更新者';


--
-- Name: COLUMN infra_codegen_column.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.update_time IS '更新时间';


--
-- Name: COLUMN infra_codegen_column.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_column.deleted IS '是否删除';


--
-- Name: infra_codegen_column_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_codegen_column_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_codegen_table; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_codegen_table (
    id bigint NOT NULL,
    data_source_config_id bigint NOT NULL,
    scene smallint DEFAULT 1 NOT NULL,
    table_name character varying(200) DEFAULT ''::character varying NOT NULL,
    table_comment character varying(500) DEFAULT ''::character varying NOT NULL,
    remark character varying(500) DEFAULT NULL::character varying,
    module_name character varying(30) NOT NULL,
    business_name character varying(30) NOT NULL,
    class_name character varying(100) DEFAULT ''::character varying NOT NULL,
    class_comment character varying(50) NOT NULL,
    author character varying(50) NOT NULL,
    template_type smallint DEFAULT 1 NOT NULL,
    front_type smallint NOT NULL,
    parent_menu_id bigint,
    master_table_id bigint,
    sub_join_column_id bigint,
    sub_join_many boolean,
    tree_parent_column_id bigint,
    tree_name_column_id bigint,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_codegen_table; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_codegen_table IS '代码生成表定义';


--
-- Name: COLUMN infra_codegen_table.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.id IS '编号';


--
-- Name: COLUMN infra_codegen_table.data_source_config_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.data_source_config_id IS '数据源配置的编号';


--
-- Name: COLUMN infra_codegen_table.scene; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.scene IS '生成场景';


--
-- Name: COLUMN infra_codegen_table.table_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.table_name IS '表名称';


--
-- Name: COLUMN infra_codegen_table.table_comment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.table_comment IS '表描述';


--
-- Name: COLUMN infra_codegen_table.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.remark IS '备注';


--
-- Name: COLUMN infra_codegen_table.module_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.module_name IS '模块名';


--
-- Name: COLUMN infra_codegen_table.business_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.business_name IS '业务名';


--
-- Name: COLUMN infra_codegen_table.class_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.class_name IS '类名称';


--
-- Name: COLUMN infra_codegen_table.class_comment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.class_comment IS '类描述';


--
-- Name: COLUMN infra_codegen_table.author; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.author IS '作者';


--
-- Name: COLUMN infra_codegen_table.template_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.template_type IS '模板类型';


--
-- Name: COLUMN infra_codegen_table.front_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.front_type IS '前端类型';


--
-- Name: COLUMN infra_codegen_table.parent_menu_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.parent_menu_id IS '父菜单编号';


--
-- Name: COLUMN infra_codegen_table.master_table_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.master_table_id IS '主表的编号';


--
-- Name: COLUMN infra_codegen_table.sub_join_column_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.sub_join_column_id IS '子表关联主表的字段编号';


--
-- Name: COLUMN infra_codegen_table.sub_join_many; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.sub_join_many IS '主表与子表是否一对多';


--
-- Name: COLUMN infra_codegen_table.tree_parent_column_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.tree_parent_column_id IS '树表的父字段编号';


--
-- Name: COLUMN infra_codegen_table.tree_name_column_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.tree_name_column_id IS '树表的名字字段编号';


--
-- Name: COLUMN infra_codegen_table.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.creator IS '创建者';


--
-- Name: COLUMN infra_codegen_table.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.create_time IS '创建时间';


--
-- Name: COLUMN infra_codegen_table.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.updater IS '更新者';


--
-- Name: COLUMN infra_codegen_table.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.update_time IS '更新时间';


--
-- Name: COLUMN infra_codegen_table.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_codegen_table.deleted IS '是否删除';


--
-- Name: infra_codegen_table_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_codegen_table_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_config (
    id bigint NOT NULL,
    category character varying(50) NOT NULL,
    type smallint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    config_key character varying(100) DEFAULT ''::character varying NOT NULL,
    value character varying(500) DEFAULT ''::character varying NOT NULL,
    visible boolean NOT NULL,
    remark character varying(500) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_config IS '参数配置表';


--
-- Name: COLUMN infra_config.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.id IS '参数主键';


--
-- Name: COLUMN infra_config.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.category IS '参数分组';


--
-- Name: COLUMN infra_config.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.type IS '参数类型';


--
-- Name: COLUMN infra_config.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.name IS '参数名称';


--
-- Name: COLUMN infra_config.config_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.config_key IS '参数键名';


--
-- Name: COLUMN infra_config.value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.value IS '参数键值';


--
-- Name: COLUMN infra_config.visible; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.visible IS '是否可见';


--
-- Name: COLUMN infra_config.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.remark IS '备注';


--
-- Name: COLUMN infra_config.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.creator IS '创建者';


--
-- Name: COLUMN infra_config.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.create_time IS '创建时间';


--
-- Name: COLUMN infra_config.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.updater IS '更新者';


--
-- Name: COLUMN infra_config.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.update_time IS '更新时间';


--
-- Name: COLUMN infra_config.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_config.deleted IS '是否删除';


--
-- Name: infra_config_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_config_seq
    START WITH 14
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_data_source_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_data_source_config (
    id bigint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    url character varying(1024) NOT NULL,
    username character varying(255) NOT NULL,
    password character varying(255) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_data_source_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_data_source_config IS '数据源配置表';


--
-- Name: COLUMN infra_data_source_config.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.id IS '主键编号';


--
-- Name: COLUMN infra_data_source_config.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.name IS '参数名称';


--
-- Name: COLUMN infra_data_source_config.url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.url IS '数据源连接';


--
-- Name: COLUMN infra_data_source_config.username; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.username IS '用户名';


--
-- Name: COLUMN infra_data_source_config.password; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.password IS '密码';


--
-- Name: COLUMN infra_data_source_config.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.creator IS '创建者';


--
-- Name: COLUMN infra_data_source_config.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.create_time IS '创建时间';


--
-- Name: COLUMN infra_data_source_config.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.updater IS '更新者';


--
-- Name: COLUMN infra_data_source_config.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.update_time IS '更新时间';


--
-- Name: COLUMN infra_data_source_config.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_data_source_config.deleted IS '是否删除';


--
-- Name: infra_data_source_config_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_data_source_config_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_file (
    id bigint NOT NULL,
    config_id bigint,
    name character varying(256) DEFAULT NULL::character varying,
    path character varying(512) NOT NULL,
    url character varying(1024) NOT NULL,
    type character varying(128) DEFAULT NULL::character varying,
    size integer NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    protected_flag boolean DEFAULT false NOT NULL
);


--
-- Name: TABLE infra_file; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_file IS '文件表';


--
-- Name: COLUMN infra_file.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.id IS '文件编号';


--
-- Name: COLUMN infra_file.config_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.config_id IS '配置编号';


--
-- Name: COLUMN infra_file.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.name IS '文件名';


--
-- Name: COLUMN infra_file.path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.path IS '文件路径';


--
-- Name: COLUMN infra_file.url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.url IS '文件 URL';


--
-- Name: COLUMN infra_file.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.type IS '文件类型';


--
-- Name: COLUMN infra_file.size; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.size IS '文件大小';


--
-- Name: COLUMN infra_file.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.creator IS '创建者';


--
-- Name: COLUMN infra_file.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.create_time IS '创建时间';


--
-- Name: COLUMN infra_file.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.updater IS '更新者';


--
-- Name: COLUMN infra_file.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.update_time IS '更新时间';


--
-- Name: COLUMN infra_file.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.deleted IS '是否删除';


--
-- Name: COLUMN infra_file.protected_flag; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file.protected_flag IS '受保护文件：内容只能由业务模块鉴权后读取，通用文件接口拒绝访问';


--
-- Name: infra_file_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_file_config (
    id bigint NOT NULL,
    name character varying(63) NOT NULL,
    storage smallint NOT NULL,
    remark character varying(255) DEFAULT NULL::character varying,
    master boolean NOT NULL,
    config character varying(4096) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_file_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_file_config IS '文件配置表';


--
-- Name: COLUMN infra_file_config.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.id IS '编号';


--
-- Name: COLUMN infra_file_config.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.name IS '配置名';


--
-- Name: COLUMN infra_file_config.storage; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.storage IS '存储器';


--
-- Name: COLUMN infra_file_config.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.remark IS '备注';


--
-- Name: COLUMN infra_file_config.master; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.master IS '是否为主配置';


--
-- Name: COLUMN infra_file_config.config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.config IS '存储配置';


--
-- Name: COLUMN infra_file_config.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.creator IS '创建者';


--
-- Name: COLUMN infra_file_config.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.create_time IS '创建时间';


--
-- Name: COLUMN infra_file_config.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.updater IS '更新者';


--
-- Name: COLUMN infra_file_config.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.update_time IS '更新时间';


--
-- Name: COLUMN infra_file_config.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_config.deleted IS '是否删除';


--
-- Name: infra_file_config_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_file_config_seq
    START WITH 36
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_file_content; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_file_content (
    id bigint NOT NULL,
    config_id bigint NOT NULL,
    path character varying(512) NOT NULL,
    content bytea NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_file_content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_file_content IS '文件表';


--
-- Name: COLUMN infra_file_content.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.id IS '编号';


--
-- Name: COLUMN infra_file_content.config_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.config_id IS '配置编号';


--
-- Name: COLUMN infra_file_content.path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.path IS '文件路径';


--
-- Name: COLUMN infra_file_content.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.content IS '文件内容';


--
-- Name: COLUMN infra_file_content.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.creator IS '创建者';


--
-- Name: COLUMN infra_file_content.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.create_time IS '创建时间';


--
-- Name: COLUMN infra_file_content.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.updater IS '更新者';


--
-- Name: COLUMN infra_file_content.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.update_time IS '更新时间';


--
-- Name: COLUMN infra_file_content.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_file_content.deleted IS '是否删除';


--
-- Name: infra_file_content_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_file_content_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_file_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_file_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_job (
    id bigint NOT NULL,
    name character varying(32) NOT NULL,
    status smallint NOT NULL,
    handler_name character varying(64) NOT NULL,
    handler_param character varying(255) DEFAULT NULL::character varying,
    cron_expression character varying(32) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    retry_interval integer DEFAULT 0 NOT NULL,
    monitor_timeout integer DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    powerjob_job_id bigint
);


--
-- Name: TABLE infra_job; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_job IS '定时任务表';


--
-- Name: COLUMN infra_job.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.id IS '任务编号';


--
-- Name: COLUMN infra_job.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.name IS '任务名称';


--
-- Name: COLUMN infra_job.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.status IS '任务状态';


--
-- Name: COLUMN infra_job.handler_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.handler_name IS '处理器的名字';


--
-- Name: COLUMN infra_job.handler_param; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.handler_param IS '处理器的参数';


--
-- Name: COLUMN infra_job.cron_expression; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.cron_expression IS 'CRON 表达式';


--
-- Name: COLUMN infra_job.retry_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.retry_count IS '重试次数';


--
-- Name: COLUMN infra_job.retry_interval; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.retry_interval IS '重试间隔';


--
-- Name: COLUMN infra_job.monitor_timeout; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.monitor_timeout IS '监控超时时间';


--
-- Name: COLUMN infra_job.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.creator IS '创建者';


--
-- Name: COLUMN infra_job.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.create_time IS '创建时间';


--
-- Name: COLUMN infra_job.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.updater IS '更新者';


--
-- Name: COLUMN infra_job.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.update_time IS '更新时间';


--
-- Name: COLUMN infra_job.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job.deleted IS '是否删除';


--
-- Name: infra_job_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.infra_job_log (
    id bigint NOT NULL,
    job_id bigint NOT NULL,
    handler_name character varying(64) NOT NULL,
    handler_param character varying(255) DEFAULT NULL::character varying,
    execute_index smallint DEFAULT 1 NOT NULL,
    begin_time timestamp(6) without time zone NOT NULL,
    end_time timestamp(6) without time zone,
    duration integer,
    status smallint NOT NULL,
    result character varying(4000) DEFAULT ''::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE infra_job_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.infra_job_log IS '定时任务日志表';


--
-- Name: COLUMN infra_job_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.id IS '日志编号';


--
-- Name: COLUMN infra_job_log.job_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.job_id IS '任务编号';


--
-- Name: COLUMN infra_job_log.handler_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.handler_name IS '处理器的名字';


--
-- Name: COLUMN infra_job_log.handler_param; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.handler_param IS '处理器的参数';


--
-- Name: COLUMN infra_job_log.execute_index; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.execute_index IS '第几次执行';


--
-- Name: COLUMN infra_job_log.begin_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.begin_time IS '开始执行时间';


--
-- Name: COLUMN infra_job_log.end_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.end_time IS '结束执行时间';


--
-- Name: COLUMN infra_job_log.duration; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.duration IS '执行时长';


--
-- Name: COLUMN infra_job_log.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.status IS '任务状态';


--
-- Name: COLUMN infra_job_log.result; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.result IS '结果数据';


--
-- Name: COLUMN infra_job_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.creator IS '创建者';


--
-- Name: COLUMN infra_job_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.create_time IS '创建时间';


--
-- Name: COLUMN infra_job_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.updater IS '更新者';


--
-- Name: COLUMN infra_job_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.update_time IS '更新时间';


--
-- Name: COLUMN infra_job_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.infra_job_log.deleted IS '是否删除';


--
-- Name: infra_job_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_job_log_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: infra_job_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.infra_job_seq
    START WITH 41
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nocode_application; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_application (
    id bigint NOT NULL,
    app_code character varying(64) NOT NULL,
    app_name character varying(160) NOT NULL,
    description character varying(2000),
    icon character varying(80),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    published_version integer,
    design_json jsonb DEFAULT '{"objects": [], "resources": []}'::jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    category character varying(100) DEFAULT ''::character varying NOT NULL,
    deleted_at timestamp(6) without time zone,
    deleted_by character varying(64),
    deleted_reason character varying(1000),
    restored_at timestamp(6) without time zone,
    restored_by character varying(64),
    restored_reason character varying(1000),
    recovery_pending boolean DEFAULT false NOT NULL,
    recovery_needs_edit boolean DEFAULT false NOT NULL,
    CONSTRAINT nocode_application_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_application_design_json_check CHECK ((jsonb_typeof(design_json) = 'object'::text)),
    CONSTRAINT nocode_application_recovery_ck CHECK ((((NOT recovery_pending) OR ((status)::text = 'DISABLED'::text)) AND ((NOT recovery_needs_edit) OR recovery_pending))),
    CONSTRAINT nocode_application_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: TABLE nocode_application; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_application IS '应用头与编辑草稿；业务数据仍属于全局对象';


--
-- Name: COLUMN nocode_application.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_application.category IS '应用管理分类；空字符串为未分类，不进入发布快照';


--
-- Name: COLUMN nocode_application.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_application.deleted_at IS '最近一次移入应用回收站的时间，恢复后保留审计';


--
-- Name: COLUMN nocode_application.recovery_pending; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_application.recovery_pending IS '回收站恢复后必须重新发布启用，不允许直接启用旧版本';


--
-- Name: COLUMN nocode_application.recovery_needs_edit; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_application.recovery_needs_edit IS '恢复后尚未人工保存草稿，禁止发布启用';


--
-- Name: nocode_application_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_application_access (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    policy_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_application_access_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_application_access_policy_json_check CHECK ((jsonb_typeof(policy_json) = 'array'::text))
);


--
-- Name: TABLE nocode_application_access; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_application_access IS '应用成员、对象操作、记录范围和字段授权；身份复用系统用户与角色';


--
-- Name: nocode_application_access_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_application_access ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_application_access_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_application_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_application ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_application_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_application_object_follow; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_application_object_follow (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    object_id bigint NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    state character varying(16) DEFAULT 'FOLLOWING'::character varying NOT NULL,
    pending_version integer,
    pending_code character varying(24),
    pending_reason character varying(1000),
    followed_version integer,
    followed_at timestamp(6) without time zone,
    lock_version integer DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_application_object_follow_code CHECK (((pending_code IS NULL) OR ((pending_code)::text = ANY (ARRAY[('IN_FLIGHT'::character varying)::text, ('VALIDATION'::character varying)::text, ('ERROR'::character varying)::text])))),
    CONSTRAINT nocode_application_object_follow_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_application_object_follow_pending CHECK ((((state)::text = 'PENDING'::text) = ((pending_version IS NOT NULL) AND (pending_code IS NOT NULL)))),
    CONSTRAINT nocode_application_object_follow_state CHECK (((state)::text = ANY (ARRAY[('FOLLOWING'::character varying)::text, ('PENDING'::character varying)::text])))
);


--
-- Name: TABLE nocode_application_object_follow; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_application_object_follow IS '应用对数据对象的自动跟随开关与状态；没有行表示默认开启';


--
-- Name: nocode_application_object_follow_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_application_object_follow ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_application_object_follow_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_application_object_follow_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_application_object_follow_log (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    object_id bigint NOT NULL,
    from_version integer NOT NULL,
    to_version integer NOT NULL,
    application_version_before integer,
    application_version_after integer,
    outcome character varying(16) NOT NULL,
    pending_code character varying(24),
    reason character varying(1000),
    trigger_kind character varying(24) NOT NULL,
    plan_id uuid,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_application_object_follow_log_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_application_object_follow_log_outcome CHECK (((outcome)::text = ANY (ARRAY[('FOLLOWED'::character varying)::text, ('PENDING'::character varying)::text]))),
    CONSTRAINT nocode_application_object_follow_log_trigger CHECK (((trigger_kind)::text = ANY (ARRAY[('OBJECT_PUBLISH'::character varying)::text, ('SWITCH_ON'::character varying)::text, ('MANUAL'::character varying)::text, ('RETRY_JOB'::character varying)::text, ('APPLICATION_PUBLISH'::character varying)::text, ('MIGRATION'::character varying)::text])))
);


--
-- Name: TABLE nocode_application_object_follow_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_application_object_follow_log IS '自动跟随的逐次结果，只增不改';


--
-- Name: nocode_application_object_follow_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_application_object_follow_log ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_application_object_follow_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_application_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_application_version (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    version_no integer NOT NULL,
    definition_json jsonb NOT NULL,
    checksum character varying(64) NOT NULL,
    reason character varying(1000) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_application_version_definition_json_check CHECK ((jsonb_typeof(definition_json) = 'object'::text)),
    CONSTRAINT nocode_application_version_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_application_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: TABLE nocode_application_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_application_version IS '不可变应用发布版本，保存精确对象引用和资源配置';


--
-- Name: nocode_application_version_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_application_version ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_application_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_biz_attachment_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_biz_attachment_binding (
    id bigint NOT NULL,
    object_id character varying(128) NOT NULL,
    record_id character varying(512) NOT NULL,
    detail_id character varying(128) DEFAULT ''::character varying NOT NULL,
    row_id character varying(128) DEFAULT ''::character varying NOT NULL,
    field_id character varying(128) NOT NULL,
    file_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    space_id bigint NOT NULL,
    state character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    source_entry character varying(32) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    file_name character varying(255) DEFAULT ''::character varying NOT NULL,
    file_size bigint DEFAULT 0 NOT NULL,
    mime_type character varying(128) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT nocode_biz_attachment_binding_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_biz_attachment_binding_state_ck CHECK (((state)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('HISTORY'::character varying)::text])))
);


--
-- Name: TABLE nocode_biz_attachment_binding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_biz_attachment_binding IS '业务附件绑定：字段值中的 fileId 与网盘文件节点/空间的对应关系；移除附件转 HISTORY，不物理删除';


--
-- Name: COLUMN nocode_biz_attachment_binding.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_attachment_binding.state IS 'ACTIVE 当前有效、HISTORY 已从当前值移除但历史引用仍保留';


--
-- Name: COLUMN nocode_biz_attachment_binding.source_entry; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_attachment_binding.source_entry IS '上传来源入口标识（应用/维护/任务），仅作来源信息，不参与归属判定';


--
-- Name: COLUMN nocode_biz_attachment_binding.file_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_attachment_binding.file_name IS '受管网盘节点展示名（绑定后不可变）；列表、搜索与统计与权限条件在同一 SQL 内过滤';


--
-- Name: COLUMN nocode_biz_attachment_binding.file_size; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_attachment_binding.file_size IS '内容大小（字节），绑定时自文件底座冗余；用于权限过滤后的容量统计';


--
-- Name: COLUMN nocode_biz_attachment_binding.mime_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_attachment_binding.mime_type IS '内容 MIME 类型，绑定时自文件底座冗余；仅用于展示';


--
-- Name: nocode_biz_attachment_binding_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_biz_attachment_binding ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_biz_attachment_binding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_biz_directory_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_biz_directory_binding (
    id bigint NOT NULL,
    object_id character varying(128) NOT NULL,
    record_id character varying(512) NOT NULL,
    detail_id character varying(128) DEFAULT ''::character varying NOT NULL,
    row_id character varying(128) DEFAULT ''::character varying NOT NULL,
    field_id character varying(128) DEFAULT ''::character varying NOT NULL,
    space_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    rule_version integer NOT NULL,
    group_keys character varying(512) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_biz_directory_binding_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_biz_directory_binding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_biz_directory_binding IS '业务目录绑定：记录/明细区/明细行/字段身份到网盘目录节点的稳定映射；field_id 为空表示记录/明细区/明细行目录本身';


--
-- Name: COLUMN nocode_biz_directory_binding.detail_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_directory_binding.detail_id IS '所属内部明细稳定 ID，空串表示主表';


--
-- Name: COLUMN nocode_biz_directory_binding.row_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_directory_binding.row_id IS '内部明细行持久 ID，空串表示非明细行级目录';


--
-- Name: COLUMN nocode_biz_directory_binding.field_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_directory_binding.field_id IS '附件字段稳定 ID，空串表示记录目录/明细区目录/明细行目录本身';


--
-- Name: COLUMN nocode_biz_directory_binding.rule_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_directory_binding.rule_version IS '建立绑定时生效的对象发布版本号；已有记录沿用该版本，新规则只作用于新记录';


--
-- Name: COLUMN nocode_biz_directory_binding.group_keys; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_directory_binding.group_keys IS '业务分组稳定键串（关联记录 ID 等），业务值变更时据此判断是否调整目录';


--
-- Name: nocode_biz_directory_binding_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_biz_directory_binding ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_biz_directory_binding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_biz_file_mark; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_biz_file_mark (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    object_id character varying(128) NOT NULL,
    entry_id bigint NOT NULL,
    mark_type character varying(16) NOT NULL,
    access_time timestamp without time zone,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_biz_file_mark_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_biz_file_mark_type_ck CHECK (((mark_type)::text = ANY (ARRAY[('FAVORITE'::character varying)::text, ('RECENT'::character varying)::text])))
);


--
-- Name: TABLE nocode_biz_file_mark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_biz_file_mark IS '业务文件用户标记：收藏与最近访问，按用户独立，不改变节点授权也不作为读取依据';


--
-- Name: COLUMN nocode_biz_file_mark.entry_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_file_mark.entry_id IS '网盘受管节点身份，文件内容仍按业务位置身份重新校验';


--
-- Name: COLUMN nocode_biz_file_mark.mark_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_file_mark.mark_type IS '标记类型：FAVORITE 收藏、RECENT 最近访问';


--
-- Name: COLUMN nocode_biz_file_mark.access_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_file_mark.access_time IS '最近访问时间，仅 RECENT 使用；收藏按登记时间排序';


--
-- Name: nocode_biz_file_mark_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_biz_file_mark ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_biz_file_mark_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_biz_file_retention; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_biz_file_retention (
    id bigint NOT NULL,
    file_id bigint NOT NULL,
    holder_type character varying(32) NOT NULL,
    holder_id character varying(128) NOT NULL,
    object_id character varying(128) NOT NULL,
    record_id character varying(512),
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_biz_file_retention_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_biz_file_retention; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_biz_file_retention IS '文件保留引用：历史修订/工作草稿/提交材料等持有者的登记；有有效引用的文件不进入物理清理';


--
-- Name: COLUMN nocode_biz_file_retention.holder_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_file_retention.holder_type IS '持有者类型：RECORD_HISTORY 记录历史、WORK_DRAFT 工作草稿、WORK_SUBMISSION 提交材料';


--
-- Name: nocode_biz_file_retention_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_biz_file_retention ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_biz_file_retention_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_biz_file_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_biz_file_task (
    id bigint NOT NULL,
    task_type character varying(32) NOT NULL,
    file_id bigint,
    payload_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    last_error character varying(2000),
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_biz_file_task_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_biz_file_task_state_ck CHECK (((state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('RUNNING'::character varying)::text, ('DONE'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: TABLE nocode_biz_file_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_biz_file_task IS '文件操作任务：物理删除补偿、目录整理等异步动作的持久状态；只登记已知操作，不扫描无归属文件';


--
-- Name: nocode_biz_file_task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_biz_file_task ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_biz_file_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_biz_upload_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_biz_upload_session (
    id bigint NOT NULL,
    session_key character varying(64) NOT NULL,
    user_id bigint NOT NULL,
    object_id character varying(128) NOT NULL,
    field_id character varying(128) NOT NULL,
    record_id character varying(512),
    file_id bigint NOT NULL,
    file_name character varying(255) NOT NULL,
    state character varying(16) DEFAULT 'TEMPORARY'::character varying NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    idempotency_key character varying(128) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_biz_upload_session_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_biz_upload_session_state_ck CHECK (((state)::text = ANY (ARRAY[('TEMPORARY'::character varying)::text, ('BINDING'::character varying)::text, ('BOUND'::character varying)::text, ('EXPIRED'::character varying)::text, ('CLEANED'::character varying)::text])))
);


--
-- Name: TABLE nocode_biz_upload_session; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_biz_upload_session IS '业务附件受保护上传会话：保存成功前仅上传者可用；有效期默认 24 小时，被有效草稿引用时续留';


--
-- Name: COLUMN nocode_biz_upload_session.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_upload_session.state IS 'TEMPORARY 待保存、BOUND 已绑定记录、EXPIRED 已过期待清理、CLEANED 已清理';


--
-- Name: COLUMN nocode_biz_upload_session.idempotency_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_biz_upload_session.idempotency_key IS '上传幂等键：仅在同一用户/对象/字段/编辑会话的有效临时上传内唯一';


--
-- Name: nocode_biz_upload_session_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_biz_upload_session ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_biz_upload_session_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_business_counter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_business_counter (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    field_id bigint NOT NULL,
    period_key character varying(16) NOT NULL,
    next_value bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_business_counter_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_business_counter_next_value_check CHECK ((next_value > 0))
);


--
-- Name: TABLE nocode_business_counter; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_business_counter IS '应用业务编号的事务计数器，按全局对象字段和周期隔离';


--
-- Name: nocode_business_counter_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_business_counter ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_business_counter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_date_trigger_done; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_date_trigger_done (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    resource_id character varying(64) NOT NULL,
    business_date date NOT NULL,
    source_record_id character varying(500) NOT NULL,
    outcome character varying(16) NOT NULL,
    target_count integer DEFAULT 0 NOT NULL,
    message character varying(1000),
    done_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_date_trigger_done_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_date_trigger_done_outcome CHECK (((outcome)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('SUCCESS'::character varying)::text, ('UNCHANGED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: TABLE nocode_date_trigger_done; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_date_trigger_done IS '按日期自动执行：某规则某业务日已处理的来源记录（同一天不重复执行的唯一性）；保留 90 天';


--
-- Name: nocode_date_trigger_done_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_date_trigger_done ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_date_trigger_done_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_date_trigger_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_date_trigger_state (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    resource_id character varying(64) NOT NULL,
    armed boolean DEFAULT true NOT NULL,
    closed_date date NOT NULL,
    armed_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_scan_at timestamp(6) without time zone,
    last_scan_date date,
    last_trigger character varying(16),
    last_error character varying(1000),
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_date_trigger_state_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_date_trigger_state_trigger CHECK (((last_trigger IS NULL) OR ((last_trigger)::text = ANY (ARRAY[('AUTO'::character varying)::text, ('MANUAL'::character varying)::text]))))
);


--
-- Name: TABLE nocode_date_trigger_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_date_trigger_state IS '按日期自动执行：每条规则的账本；closed_date 及以前的日期不再处理';


--
-- Name: nocode_date_trigger_state_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_date_trigger_state ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_date_trigger_state_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_deployment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_deployment (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    version_no integer NOT NULL,
    schema_name character varying(63) NOT NULL,
    table_name character varying(63) NOT NULL,
    structure_hash character(64) NOT NULL,
    structure_json jsonb NOT NULL,
    verified_at timestamp with time zone DEFAULT now() NOT NULL,
    deployed_by bigint NOT NULL,
    deployed_at timestamp with time zone DEFAULT now() NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_deployment_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_deployment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_deployment IS '每次结构发布后的实际物理结构基线';


--
-- Name: COLUMN nocode_deployment.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_deployment.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_deployment.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_deployment.create_time IS '创建时间';


--
-- Name: COLUMN nocode_deployment.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_deployment.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_deployment.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_deployment.update_time IS '更新时间';


--
-- Name: COLUMN nocode_deployment.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_deployment.deleted IS '是否删除：0 否，1 是';


--
-- Name: nocode_deployment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_deployment ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_deployment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_detail_position; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_detail_position (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    detail_id bigint NOT NULL,
    parent_id text NOT NULL,
    record_id text NOT NULL,
    "position" integer NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_detail_position_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_detail_position_position_check CHECK ((("position" >= 0) AND ("position" < 500)))
);


--
-- Name: TABLE nocode_detail_position; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_detail_position IS '内部明细在整张单据中的顺序，仅由所属主单据事务维护；旧明细按原查询顺序兼容';


--
-- Name: nocode_detail_position_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.nocode_detail_position_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nocode_detail_position_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.nocode_detail_position_id_seq OWNED BY public.nocode_detail_position.id;


--
-- Name: nocode_document_receipt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_document_receipt (
    id bigint NOT NULL,
    application_id bigint,
    object_id bigint NOT NULL,
    operation character varying(16) NOT NULL,
    request_key character varying(128) NOT NULL,
    request_digest character varying(64) NOT NULL,
    operation_id uuid NOT NULL,
    record_id text NOT NULL,
    record_revision text NOT NULL,
    policy_version character varying(64) NOT NULL,
    result_json jsonb NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_document_receipt_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_document_receipt_operation_check CHECK (((operation)::text = 'SAVE'::text))
);


--
-- Name: TABLE nocode_document_receipt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_document_receipt IS '整单成功收据：请求键永久去重，读取时重新核验业务权限；失败事务不留成功收据';


--
-- Name: nocode_document_receipt_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.nocode_document_receipt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nocode_document_receipt_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.nocode_document_receipt_id_seq OWNED BY public.nocode_document_receipt.id;


--
-- Name: nocode_field; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_field (
    id bigint NOT NULL,
    object_version_id bigint NOT NULL,
    object_table_id bigint NOT NULL,
    stable_field_id bigint NOT NULL,
    field_code character varying(64) NOT NULL,
    field_name character varying(128) NOT NULL,
    column_name character varying(63) NOT NULL,
    data_type character varying(32) NOT NULL,
    storage_type character varying(24) DEFAULT 'COLUMN'::character varying NOT NULL,
    length_value integer,
    precision_value integer,
    scale_value integer,
    required_flag boolean DEFAULT false NOT NULL,
    unique_flag boolean DEFAULT false NOT NULL,
    data_classification character varying(24) DEFAULT 'NORMAL'::character varying NOT NULL,
    default_value_ast jsonb,
    validation_ast jsonb,
    field_state character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    relation_generated boolean DEFAULT false NOT NULL,
    display_resolver_type character varying(24) DEFAULT 'NONE'::character varying NOT NULL,
    display_resolver_target_id bigint,
    option_set_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    display_config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_field_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: COLUMN nocode_field.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_field.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_field.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_field.create_time IS '创建时间';


--
-- Name: COLUMN nocode_field.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_field.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_field.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_field.update_time IS '更新时间';


--
-- Name: COLUMN nocode_field.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_field.deleted IS '是否删除：0 否，1 是';


--
-- Name: nocode_field_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_field ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_flow_task_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_flow_task_binding (
    task_id character varying(128) NOT NULL,
    task_json jsonb NOT NULL,
    submission_id character varying(36),
    submitter character varying(64),
    request_digest character varying(64),
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_flow_task_binding_deleted_ck CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_flow_task_binding_json_ck CHECK ((jsonb_typeof(task_json) = 'object'::text)),
    CONSTRAINT nocode_flow_task_binding_result_ck CHECK ((((submission_id IS NULL) AND (submitter IS NULL) AND (request_digest IS NULL)) OR ((submission_id IS NOT NULL) AND (submitter IS NOT NULL) AND (request_digest IS NOT NULL))))
);


--
-- Name: TABLE nocode_flow_task_binding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_flow_task_binding IS '真实引擎任务固定资源与提交关联，不拥有另一套任务状态';


--
-- Name: nocode_handling_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_handling_request (
    id uuid NOT NULL,
    application_id bigint NOT NULL,
    application_name character varying(200) NOT NULL,
    application_version integer NOT NULL,
    object_id bigint NOT NULL,
    object_name character varying(200) NOT NULL,
    entry_id character varying(128),
    record_id character varying(512),
    operation character varying(16) NOT NULL,
    name character varying(300) NOT NULL,
    request_key character varying(128) NOT NULL,
    request_digest character varying(64) NOT NULL,
    definition_checksum character varying(64) NOT NULL,
    definition_json jsonb NOT NULL,
    submission_id character varying(36) NOT NULL,
    process_instance_id character varying(128),
    process_definition_id character varying(128) NOT NULL,
    process_definition_key character varying(128) NOT NULL,
    status character varying(32) NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    error character varying(1000),
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_handling_request_operation_check CHECK (((operation)::text = ANY (ARRAY[('CREATE'::character varying)::text, ('UPDATE'::character varying)::text]))),
    CONSTRAINT nocode_handling_request_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPLY_PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text, ('CANCELED'::character varying)::text, ('APPLY_FAILED'::character varying)::text])))
);


--
-- Name: TABLE nocode_handling_request; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_handling_request IS '无代码业务操作申请：审批终态与业务生效分离';


--
-- Name: nocode_index_definition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_index_definition (
    id bigint NOT NULL,
    object_version_id bigint NOT NULL,
    stable_index_id bigint NOT NULL,
    index_code character varying(63) NOT NULL,
    index_name character varying(128) NOT NULL,
    unique_flag boolean DEFAULT false NOT NULL,
    field_ids_json jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    parent_scoped boolean DEFAULT false NOT NULL,
    CONSTRAINT nocode_index_definition_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: COLUMN nocode_index_definition.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_index_definition.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_index_definition.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_index_definition.create_time IS '创建时间';


--
-- Name: COLUMN nocode_index_definition.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_index_definition.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_index_definition.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_index_definition.update_time IS '更新时间';


--
-- Name: COLUMN nocode_index_definition.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_index_definition.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN nocode_index_definition.parent_scoped; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_index_definition.parent_scoped IS '是否在内部明细父记录范围内建立索引';


--
-- Name: nocode_index_definition_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_index_definition ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_index_definition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_linkage_trigger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_linkage_trigger (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    application_version integer NOT NULL,
    source_object_id bigint NOT NULL,
    target_object_id bigint NOT NULL,
    target_object_version integer NOT NULL,
    target_field_id bigint NOT NULL,
    anchor character varying(16) NOT NULL,
    anchor_field_id bigint NOT NULL,
    signature character varying(64) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_linkage_trigger_anchor CHECK (((anchor)::text = ANY (ARRAY[('CURRENT_RECORD'::character varying)::text, ('RECORD_KEY'::character varying)::text])))
);


--
-- Name: nocode_linkage_trigger_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_linkage_trigger ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_linkage_trigger_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_object; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_object (
    id bigint NOT NULL,
    object_code character varying(64) NOT NULL,
    object_name character varying(128) NOT NULL,
    source_type character varying(24) DEFAULT 'GENERATED'::character varying NOT NULL,
    physical_name_seed character varying(48) NOT NULL,
    title_field_stable_id bigint NOT NULL,
    title_template character varying(512),
    title_config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    owner_field_stable_id bigint,
    org_field_stable_id bigint,
    latest_version_no integer DEFAULT 1 NOT NULL,
    current_published_version_no integer,
    description character varying(1000),
    status character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    schema_name character varying(63) DEFAULT 'public'::character varying NOT NULL,
    settings_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    read_only boolean DEFAULT false NOT NULL,
    adoption_hash character varying(64),
    deleted smallint DEFAULT 0 NOT NULL,
    reconciliation_hash character varying(64),
    reconciliation_version_id bigint,
    category character varying(100) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT nocode_object_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_object_lock_version_check CHECK ((lock_version >= 0))
);


--
-- Name: TABLE nocode_object; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_object IS '全局数据对象；草稿保存不创建业务表';


--
-- Name: COLUMN nocode_object.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_object.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.create_time IS '创建时间';


--
-- Name: COLUMN nocode_object.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_object.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.update_time IS '更新时间';


--
-- Name: COLUMN nocode_object.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN nocode_object.reconciliation_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.reconciliation_hash IS '用户已核对的物理结构摘要，普通保存立即失效';


--
-- Name: COLUMN nocode_object.reconciliation_version_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.reconciliation_version_id IS '获准同步该物理结构的草稿版本';


--
-- Name: COLUMN nocode_object.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object.category IS '数据对象管理分类；空字符串为未分类，不进入发布定义';


--
-- Name: nocode_object_application_grant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_object_application_grant (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    application_id bigint NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    grant_json jsonb NOT NULL,
    reason character varying(1000) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_object_application_grant_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_object_application_grant_grant_json_check CHECK ((jsonb_typeof(grant_json) = ANY (ARRAY['object'::text, 'null'::text])))
);


--
-- Name: TABLE nocode_object_application_grant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_object_application_grant IS '对象对应用的授权上限；应用内部授权和创建人均不得超出；独立于发布快照';


--
-- Name: nocode_object_application_grant_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_object_application_grant ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_object_application_grant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_object_application_grant_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_object_application_grant_log (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    application_id bigint NOT NULL,
    lock_version integer NOT NULL,
    grant_json jsonb NOT NULL,
    reason character varying(1000) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_object_application_grant_log_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_object_application_grant_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_object_application_grant_log IS '对象共享授权变更审计；包括迁移固化、收紧和撤销';


--
-- Name: nocode_object_application_grant_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_object_application_grant_log ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_object_application_grant_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_object_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_object ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_object_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_object_table; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_object_table (
    id bigint NOT NULL,
    object_version_id bigint NOT NULL,
    stable_table_id bigint NOT NULL,
    table_code character varying(64) NOT NULL,
    table_name character varying(63) NOT NULL,
    table_role character varying(24) NOT NULL,
    parent_stable_table_id bigint,
    detail_no integer,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_object_table_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_object_table_generated_name CHECK (((table_name)::text ~ '^(biz_|nocode_data_)[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT nocode_object_table_table_role_check CHECK (((table_role)::text = ANY (ARRAY[('MAIN'::character varying)::text, ('DETAIL'::character varying)::text])))
);


--
-- Name: COLUMN nocode_object_table.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_table.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_object_table.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_table.create_time IS '创建时间';


--
-- Name: COLUMN nocode_object_table.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_table.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_object_table.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_table.update_time IS '更新时间';


--
-- Name: COLUMN nocode_object_table.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_table.deleted IS '是否删除：0 否，1 是';


--
-- Name: nocode_object_table_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_object_table ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_object_table_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_object_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_object_version (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    version_no integer NOT NULL,
    base_version_no integer,
    schema_version integer DEFAULT 1 NOT NULL,
    state character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    schema_json jsonb NOT NULL,
    schema_checksum character(64) NOT NULL,
    change_summary_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    published_by bigint,
    published_at timestamp with time zone,
    lock_version integer DEFAULT 0 NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_object_version_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: COLUMN nocode_object_version.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_version.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_object_version.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_version.create_time IS '创建时间';


--
-- Name: COLUMN nocode_object_version.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_version.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_object_version.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_version.update_time IS '更新时间';


--
-- Name: COLUMN nocode_object_version.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_object_version.deleted IS '是否删除：0 否，1 是';


--
-- Name: nocode_object_version_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_object_version ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_object_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_operation_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_operation_log (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    trace_id uuid NOT NULL,
    correlation_id uuid,
    operator_id bigint,
    app_id bigint,
    operation_type character varying(64) NOT NULL,
    resource_type character varying(64) NOT NULL,
    resource_id character varying(128),
    object_id bigint,
    business_id bigint,
    result_status character varying(24) NOT NULL,
    reason character varying(1000),
    client_ip inet,
    user_agent character varying(1000),
    detail_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_operation_log_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_operation_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_operation_log IS '无代码对象修订的领域留痕，与元数据同事务；通用操作日志继续复用底座';


--
-- Name: COLUMN nocode_operation_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_operation_log.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_operation_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_operation_log.create_time IS '创建时间';


--
-- Name: COLUMN nocode_operation_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_operation_log.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_operation_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_operation_log.update_time IS '更新时间';


--
-- Name: COLUMN nocode_operation_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_operation_log.deleted IS '是否删除：0 否，1 是';


--
-- Name: nocode_operation_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_operation_log ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_operation_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_ordered_calculation_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_ordered_calculation_state (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    field_id bigint NOT NULL,
    signature character varying(64) NOT NULL,
    state character varying(24) NOT NULL,
    cursor_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    total_rows bigint DEFAULT 0 NOT NULL,
    updated_rows bigint DEFAULT 0 NOT NULL,
    completed_groups bigint DEFAULT 0 NOT NULL,
    error_message character varying(2000),
    lock_version bigint DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_ordered_calculation_state_code CHECK (((state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('BACKFILLING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: nocode_ordered_calculation_state_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_ordered_calculation_state ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_ordered_calculation_state_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_publish_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_publish_plan (
    id uuid NOT NULL,
    object_id bigint NOT NULL,
    object_version_id bigint NOT NULL,
    version_no integer NOT NULL,
    revision integer NOT NULL,
    schema_checksum character(64) NOT NULL,
    baseline_hash character varying(64) NOT NULL,
    plan_json jsonb NOT NULL,
    state character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    executed_by bigint,
    executed_at timestamp with time zone,
    reason character varying(1000),
    error_message character varying(1000),
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_publish_plan_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_publish_plan; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_publish_plan IS '绑定草稿修订与物理指纹的结构发布计划及执行结果';


--
-- Name: COLUMN nocode_publish_plan.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_publish_plan.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_publish_plan.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_publish_plan.create_time IS '创建时间';


--
-- Name: COLUMN nocode_publish_plan.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_publish_plan.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_publish_plan.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_publish_plan.update_time IS '更新时间';


--
-- Name: COLUMN nocode_publish_plan.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_publish_plan.deleted IS '是否删除：0 否，1 是';


--
-- Name: nocode_record_folder_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_record_folder_binding (
    id bigint NOT NULL,
    object_id character varying(128) NOT NULL,
    record_id character varying(512) NOT NULL,
    source_id bigint NOT NULL,
    anchor_entry_id bigint DEFAULT 0 NOT NULL,
    space_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    origin character varying(16) DEFAULT 'AUTO'::character varying NOT NULL,
    auto_name character varying(255) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_record_folder_binding_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_record_folder_binding_origin CHECK (((origin)::text = ANY (ARRAY[('AUTO'::character varying)::text, ('MANUAL'::character varying)::text])))
);


--
-- Name: TABLE nocode_record_folder_binding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_record_folder_binding IS '记录文件夹对应关系：记录在某来源下的子文件夹；按网盘节点编号绑定，文件夹改名或移动不影响';


--
-- Name: COLUMN nocode_record_folder_binding.anchor_entry_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_binding.anchor_entry_id IS '子文件夹建在谁下面：FOLDER 来源恒为 0；RELATION 来源为关联记录解析出的文件夹编号';


--
-- Name: COLUMN nocode_record_folder_binding.origin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_binding.origin IS 'AUTO 系统在首次写入时建立；MANUAL 人工指定（二期）';


--
-- Name: COLUMN nocode_record_folder_binding.auto_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_binding.auto_name IS '建立时系统取的名字，供二期判断文件夹是否被人工改过名';


--
-- Name: nocode_record_folder_binding_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_record_folder_binding ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_record_folder_binding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_record_folder_source; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_record_folder_source (
    id bigint NOT NULL,
    object_id character varying(128) NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    label character varying(40) DEFAULT ''::character varying NOT NULL,
    kind character varying(16) NOT NULL,
    placement character varying(24) NOT NULL,
    space_id bigint,
    entry_id bigint,
    relation_field_id character varying(128) DEFAULT ''::character varying NOT NULL,
    target_source_id bigint,
    create_mode character varying(16) DEFAULT 'ON_FIRST_WRITE'::character varying NOT NULL,
    name_template character varying(2000) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_record_folder_source_create_mode CHECK (((create_mode)::text = ANY (ARRAY[('ON_FIRST_WRITE'::character varying)::text, ('ON_SAVE'::character varying)::text]))),
    CONSTRAINT nocode_record_folder_source_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_record_folder_source_kind CHECK (((kind)::text = ANY (ARRAY[('FOLDER'::character varying)::text, ('RELATION'::character varying)::text]))),
    CONSTRAINT nocode_record_folder_source_placement CHECK (((placement)::text = ANY (ARRAY[('DIRECT'::character varying)::text, ('RECORD_SUBFOLDER'::character varying)::text]))),
    CONSTRAINT nocode_record_folder_source_shape CHECK (((((kind)::text = 'FOLDER'::text) AND (space_id IS NOT NULL) AND (entry_id IS NOT NULL) AND ((relation_field_id)::text = ''::text) AND (target_source_id IS NULL)) OR (((kind)::text = 'RELATION'::text) AND (space_id IS NULL) AND (entry_id IS NULL) AND ((relation_field_id)::text <> ''::text) AND (target_source_id IS NOT NULL))))
);


--
-- Name: TABLE nocode_record_folder_source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_record_folder_source IS '记录文件夹来源：一行 = 某对象表单下方的一个文件夹页签；不随对象版本冻结';


--
-- Name: COLUMN nocode_record_folder_source.kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_source.kind IS 'FOLDER 指定网盘里的一个文件夹；RELATION 用关联记录的文件夹';


--
-- Name: COLUMN nocode_record_folder_source.placement; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_source.placement IS 'DIRECT 直接用那个文件夹；RECORD_SUBFOLDER 在其中为本记录建一个子文件夹';


--
-- Name: COLUMN nocode_record_folder_source.entry_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_source.entry_id IS 'FOLDER：指定的网盘目录节点编号（普通节点）';


--
-- Name: COLUMN nocode_record_folder_source.relation_field_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_source.relation_field_id IS 'RELATION：本对象主表上的单值关联字段稳定 ID';


--
-- Name: COLUMN nocode_record_folder_source.target_source_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_source.target_source_id IS 'RELATION：对方对象的文件夹来源编号';


--
-- Name: COLUMN nocode_record_folder_source.create_mode; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_source.create_mode IS '子文件夹的建立时机：ON_FIRST_WRITE 第一次写入时；ON_SAVE 记录保存提交后由后台建立。仅 RECORD_SUBFOLDER 有意义';


--
-- Name: COLUMN nocode_record_folder_source.name_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_folder_source.name_template IS '子文件夹命名模板的 JSON 文本；空串表示用记录名称。仅 RECORD_SUBFOLDER 有意义';


--
-- Name: nocode_record_folder_source_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_record_folder_source ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_record_folder_source_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_record_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_record_history (
    id bigint NOT NULL,
    object_id bigint NOT NULL,
    record_id text NOT NULL,
    application_id bigint,
    operation character varying(16) NOT NULL,
    occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    before_json jsonb,
    after_json jsonb,
    definition_json jsonb NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    written_txid xid8 DEFAULT pg_current_xact_id() NOT NULL,
    operation_id uuid,
    policy_version character varying(64),
    source_json jsonb,
    CONSTRAINT nocode_record_history_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_record_history_operation_check CHECK (((operation)::text = ANY (ARRAY[('BASELINE'::character varying)::text, ('CREATE'::character varying)::text, ('UPDATE'::character varying)::text, ('DELETE'::character varying)::text])))
);


--
-- Name: TABLE nocode_record_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_record_history IS '成功业务写入的前后值；与业务事务共同提交，BASELINE 不计入变更次数';


--
-- Name: COLUMN nocode_record_history.written_txid; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_history.written_txid IS '写入事务 ID，配合 pg_current_snapshot 固定查询可见性';


--
-- Name: COLUMN nocode_record_history.operation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_history.operation_id IS '一次整单写入标识，可关联成功收据；旧事件为空';


--
-- Name: COLUMN nocode_record_history.policy_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_history.policy_version IS '本次完整性策略内容摘要，策略正文保留在 definition_json';


--
-- Name: COLUMN nocode_record_history.source_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_record_history.source_json IS '服务端生成的办理来源；历史空值不反推入口';


--
-- Name: nocode_record_history_head; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_record_history_head (
    object_id bigint NOT NULL,
    covered_from timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_record_history_head_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: nocode_record_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.nocode_record_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nocode_record_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.nocode_record_history_id_seq OWNED BY public.nocode_record_history.id;


--
-- Name: nocode_record_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_record_process (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    application_version integer NOT NULL,
    object_id bigint NOT NULL,
    object_version integer NOT NULL,
    record_id character varying(500) NOT NULL,
    action_id character varying(80) NOT NULL,
    name character varying(160) NOT NULL,
    business_key character varying(80) NOT NULL,
    process_definition_id character varying(128) NOT NULL,
    process_definition_key character varying(128) NOT NULL,
    process_instance_id character varying(128),
    status character varying(20) NOT NULL,
    end_time timestamp(6) without time zone,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_record_process_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_record_process_status_check CHECK (((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text, ('CANCELED'::character varying)::text])))
);


--
-- Name: TABLE nocode_record_process; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_record_process IS '已有业务记录与底座 BPM 流程实例关联；审批期间保护同一全局记录';


--
-- Name: nocode_record_process_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_record_process ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_record_process_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_relation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_relation (
    id bigint NOT NULL,
    object_version_id bigint NOT NULL,
    stable_relation_id bigint NOT NULL,
    relation_code character varying(63) NOT NULL,
    relation_name character varying(128) NOT NULL,
    relation_kind character varying(24) NOT NULL,
    target_object_id bigint NOT NULL,
    field_stable_id bigint,
    target_field_stable_id bigint,
    required_flag boolean DEFAULT false NOT NULL,
    on_delete character varying(16) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    source_detail_id bigint,
    CONSTRAINT nocode_relation_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_relation_detail_kind CHECK (((source_detail_id IS NULL) OR ((relation_kind)::text = 'REFERENCE'::text))),
    CONSTRAINT nocode_relation_on_delete_check CHECK (((on_delete)::text = ANY (ARRAY[('RESTRICT'::character varying)::text, ('CASCADE'::character varying)::text, ('SET_NULL'::character varying)::text]))),
    CONSTRAINT nocode_relation_relation_kind_check CHECK (((relation_kind)::text = ANY (ARRAY[('REFERENCE'::character varying)::text, ('MASTER_DETAIL'::character varying)::text, ('ONE_TO_ONE'::character varying)::text, ('MANY_TO_MANY'::character varying)::text])))
);


--
-- Name: TABLE nocode_relation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_relation IS '对象版本内的稳定关系定义；引用字段由关系维护';


--
-- Name: COLUMN nocode_relation.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_relation.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_relation.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_relation.create_time IS '创建时间';


--
-- Name: COLUMN nocode_relation.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_relation.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_relation.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_relation.update_time IS '更新时间';


--
-- Name: COLUMN nocode_relation.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_relation.deleted IS '是否删除：0 否，1 是';


--
-- Name: COLUMN nocode_relation.source_detail_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_relation.source_detail_id IS '引用来源内部明细稳定 ID；空值表示主表';


--
-- Name: nocode_relation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_relation ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_relation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_dashboard; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_dashboard (
    id bigint NOT NULL,
    name character varying(80) NOT NULL,
    owner_id bigint NOT NULL,
    draft_json jsonb NOT NULL,
    draft_checksum character varying(64) NOT NULL,
    lock_version integer DEFAULT 1 NOT NULL,
    published_version integer,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    folder_id bigint,
    CONSTRAINT nocode_report_dashboard_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_dashboard_lock_version_check CHECK ((lock_version > 0)),
    CONSTRAINT nocode_report_dashboard_published_version_check CHECK ((published_version > 0)),
    CONSTRAINT nocode_report_dashboard_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: TABLE nocode_report_dashboard; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_dashboard IS '独立仪表板草稿；拥有者不自动获得业务读取权';


--
-- Name: COLUMN nocode_report_dashboard.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_report_dashboard.status IS '停用作用于全部发布版本；发布不自动激活显式停用资源';


--
-- Name: COLUMN nocode_report_dashboard.folder_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_report_dashboard.folder_id IS '同类型分类目录，不参与发布快照及内容校验和';


--
-- Name: nocode_report_dashboard_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_dashboard ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_dashboard_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_dashboard_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_dashboard_version (
    id bigint NOT NULL,
    dashboard_id bigint NOT NULL,
    version_no integer NOT NULL,
    definition_json jsonb NOT NULL,
    checksum character varying(64) NOT NULL,
    request_id character varying(80) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_dashboard_version_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_dashboard_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: TABLE nocode_report_dashboard_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_dashboard_version IS '仪表板不可变发布版本，引用固定数据集版本';


--
-- Name: nocode_report_dashboard_version_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_dashboard_version ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_dashboard_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_dataset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_dataset (
    id bigint NOT NULL,
    name character varying(80) NOT NULL,
    description character varying(1000) DEFAULT ''::character varying NOT NULL,
    owner_id bigint NOT NULL,
    status character varying(16) DEFAULT 'INACTIVE'::character varying NOT NULL,
    draft_json jsonb NOT NULL,
    draft_checksum character varying(64) NOT NULL,
    lock_version integer DEFAULT 1 NOT NULL,
    published_version integer,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    folder_id bigint,
    CONSTRAINT nocode_report_dataset_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_dataset_lock_version_check CHECK ((lock_version > 0)),
    CONSTRAINT nocode_report_dataset_published_version_check CHECK ((published_version > 0)),
    CONSTRAINT nocode_report_dataset_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: TABLE nocode_report_dataset; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_dataset IS '报表数据集草稿和发布指针；所有权不授予业务数据读取权限';


--
-- Name: COLUMN nocode_report_dataset.folder_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_report_dataset.folder_id IS '当前分类位置，不参与发布快照与内容校验和';


--
-- Name: nocode_report_dataset_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_dataset ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_dataset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_dataset_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_dataset_policy (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    members_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    lock_version integer DEFAULT 1 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_dataset_policy_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_dataset_policy_lock_version_check CHECK ((lock_version > 0))
);


--
-- Name: TABLE nocode_report_dataset_policy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_dataset_policy IS '数据集成员读/导出策略；创建者不自动免除成员登记';


--
-- Name: nocode_report_dataset_policy_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_dataset_policy ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_dataset_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_dataset_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_dataset_version (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    version_no integer NOT NULL,
    definition_json jsonb NOT NULL,
    checksum character varying(64) NOT NULL,
    reason character varying(1000) NOT NULL,
    request_id character varying(80) NOT NULL,
    request_hash character varying(64) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_dataset_version_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_dataset_version_version_no_check CHECK ((version_no > 0))
);


--
-- Name: TABLE nocode_report_dataset_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_dataset_version IS '不可变数据集发布快照；发布幂等键绑定资源和操作者';


--
-- Name: nocode_report_dataset_version_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_dataset_version ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_dataset_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_dependency; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_dependency (
    id bigint NOT NULL,
    source_kind character varying(32) NOT NULL,
    source_id bigint NOT NULL,
    source_stage character varying(16) NOT NULL,
    source_version integer NOT NULL,
    target_kind character varying(32) NOT NULL,
    target_id bigint NOT NULL,
    target_version integer NOT NULL,
    field_refs_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_dependency_check CHECK (((((source_stage)::text = 'DRAFT'::text) AND (source_version = 0)) OR (((source_stage)::text = 'VERSION'::text) AND (source_version > 0)))),
    CONSTRAINT nocode_report_dependency_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_dependency_field_refs_json_check CHECK ((jsonb_typeof(field_refs_json) = 'array'::text)),
    CONSTRAINT nocode_report_dependency_source_kind_check CHECK (((source_kind)::text = ANY (ARRAY[('DASHBOARD'::character varying)::text, ('APPLICATION'::character varying)::text]))),
    CONSTRAINT nocode_report_dependency_source_stage_check CHECK (((source_stage)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('VERSION'::character varying)::text]))),
    CONSTRAINT nocode_report_dependency_source_version_check CHECK ((source_version >= 0)),
    CONSTRAINT nocode_report_dependency_target_kind_check CHECK (((target_kind)::text = ANY (ARRAY[('DATASET'::character varying)::text, ('DASHBOARD'::character varying)::text]))),
    CONSTRAINT nocode_report_dependency_target_version_check CHECK ((target_version > 0))
);


--
-- Name: TABLE nocode_report_dependency; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_dependency IS '报表固定引用反向索引，保留草稿及可恢复历史版本；写入方须先持全局设计锁并校验目标未删除';


--
-- Name: nocode_report_dependency_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_dependency ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_dependency_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_folder; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_folder (
    id bigint NOT NULL,
    resource_kind character varying(32) NOT NULL,
    parent_id bigint,
    name character varying(80) NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    lock_version integer DEFAULT 1 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_folder_check CHECK ((parent_id IS DISTINCT FROM id)),
    CONSTRAINT nocode_report_folder_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_folder_lock_version_check CHECK ((lock_version > 0)),
    CONSTRAINT nocode_report_folder_resource_kind_check CHECK (((resource_kind)::text = ANY (ARRAY[('DATASET'::character varying)::text, ('DASHBOARD'::character varying)::text]))),
    CONSTRAINT nocode_report_folder_sort_no_check CHECK (((sort_no >= 0) AND (sort_no <= 9999)))
);


--
-- Name: TABLE nocode_report_folder; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_folder IS '报表分类目录；同类层级、禁止环、非空不可删除；不继承内容权限';


--
-- Name: nocode_report_folder_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_folder ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_folder_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_object_grant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_object_grant (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    object_id bigint NOT NULL,
    grant_json jsonb,
    lock_version integer DEFAULT 1 NOT NULL,
    reason character varying(1000) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_object_grant_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_object_grant_lock_version_check CHECK ((lock_version > 0))
);


--
-- Name: TABLE nocode_report_object_grant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_object_grant IS '数据管理员授予数据集的对象范围上限，独立于应用共享授权';


--
-- Name: nocode_report_object_grant_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_object_grant ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_object_grant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_operation_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_operation_log (
    id bigint NOT NULL,
    resource_kind character varying(32) NOT NULL,
    resource_id bigint NOT NULL,
    action character varying(32) NOT NULL,
    revision integer NOT NULL,
    before_json jsonb,
    after_json jsonb,
    reason character varying(1000) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_operation_log_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_report_operation_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_operation_log IS '报表资源配置与授权变更审计；同事务记录，不存查询业务结果';


--
-- Name: nocode_report_operation_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_operation_log ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_operation_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_preference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_preference (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    dashboard_id bigint NOT NULL,
    favorite boolean DEFAULT false NOT NULL,
    last_visited_at timestamp without time zone,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_preference_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_report_preference; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_preference IS '个人仪表板收藏与最近访问；读取实时过滤当前资源访问权限';


--
-- Name: nocode_report_preference_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_preference ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_preference_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_report_resource_acl; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_report_resource_acl (
    id bigint NOT NULL,
    resource_kind character varying(32) NOT NULL,
    resource_id bigint NOT NULL,
    policy_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    lock_version integer DEFAULT 1 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_report_resource_acl_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_report_resource_acl_lock_version_check CHECK ((lock_version > 0)),
    CONSTRAINT nocode_report_resource_acl_resource_kind_check CHECK (((resource_kind)::text = ANY (ARRAY[('DATASET'::character varying)::text, ('DASHBOARD'::character varying)::text])))
);


--
-- Name: TABLE nocode_report_resource_acl; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_report_resource_acl IS '报表资源协作管理权限；不等价于业务数据授权';


--
-- Name: nocode_report_resource_acl_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_report_resource_acl ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_report_resource_acl_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_resource_dependency; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_resource_dependency (
    id bigint NOT NULL,
    source_kind character varying(32) NOT NULL,
    source_key character varying(160) NOT NULL,
    source_name character varying(160) NOT NULL,
    target_object_id bigint NOT NULL,
    field_ids_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_resource_dependency_deleted_check CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_resource_dependency; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_resource_dependency IS '应用、页面、视图、流程等模块登记的对象及字段依赖';


--
-- Name: COLUMN nocode_resource_dependency.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_resource_dependency.update_time IS '更新时间';


--
-- Name: COLUMN nocode_resource_dependency.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_resource_dependency.creator IS '创建者（底座用户 ID）';


--
-- Name: COLUMN nocode_resource_dependency.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_resource_dependency.create_time IS '创建时间';


--
-- Name: COLUMN nocode_resource_dependency.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_resource_dependency.updater IS '更新者（底座用户 ID）';


--
-- Name: COLUMN nocode_resource_dependency.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_resource_dependency.deleted IS '是否删除：0 否，1 是';


--
-- Name: nocode_resource_dependency_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.nocode_resource_dependency ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.nocode_resource_dependency_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: nocode_stable_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.nocode_stable_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nocode_task_comment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_comment (
    id character varying(64) NOT NULL,
    task_id character varying(64) NOT NULL,
    parent_id character varying(64),
    content text NOT NULL,
    mentioned_json text NOT NULL,
    request_key character varying(120) NOT NULL,
    request_hash character varying(64) NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: nocode_task_entry_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_entry_access (
    id bigint NOT NULL,
    application_id bigint NOT NULL,
    entry_id character varying(80) NOT NULL,
    lock_version integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    policy_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE nocode_task_entry_access; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_task_entry_access IS '任务入口实时授权和启停，不随应用版本回退';


--
-- Name: nocode_task_entry_access_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.nocode_task_entry_access_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nocode_task_entry_access_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.nocode_task_entry_access_id_seq OWNED BY public.nocode_task_entry_access.id;


--
-- Name: nocode_task_entry_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_entry_binding (
    id character varying(64) NOT NULL,
    task_id character varying(64) NOT NULL,
    entry_key character varying(80) NOT NULL,
    dataset_id character varying(160) NOT NULL,
    config_json text NOT NULL,
    business_json text NOT NULL,
    inherited boolean DEFAULT false NOT NULL,
    submitted_json text,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: nocode_task_entry_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_entry_record (
    id character varying(64) NOT NULL,
    task_id character varying(64) NOT NULL,
    entry_key character varying(80) NOT NULL,
    dataset_id character varying(160) NOT NULL,
    business_json text NOT NULL,
    operation character varying(20) NOT NULL,
    superseded_by character varying(64),
    record_revision character varying(200),
    snapshot_json text,
    request_key character varying(120) NOT NULL,
    request_hash character varying(64) NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    work_rule_json text,
    CONSTRAINT nocode_task_entry_record_operation_check CHECK (((operation)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('UPDATED'::character varying)::text, ('LINKED'::character varying)::text, ('DELETED'::character varying)::text, ('UNCHANGED'::character varying)::text])))
);


--
-- Name: COLUMN nocode_task_entry_record.work_rule_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_task_entry_record.work_rule_json IS '办理提交时工时规则；存量由 V079 按升级时实例标准封存';


--
-- Name: nocode_task_entry_template_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_entry_template_version (
    id character varying(64) NOT NULL,
    template_id character varying(64) NOT NULL,
    version_no integer NOT NULL,
    bindings_json text NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: nocode_task_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_event (
    id character varying(64) NOT NULL,
    task_id character varying(64) NOT NULL,
    root_id character varying(64) NOT NULL,
    event_type character varying(30) NOT NULL,
    note text,
    material_json text,
    request_key character varying(120),
    request_hash character varying(64),
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: nocode_task_instance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_instance (
    id character varying(64) NOT NULL,
    root_id character varying(64) NOT NULL,
    parent_id character varying(64),
    title character varying(200) NOT NULL,
    assignee_id bigint,
    status character varying(20) NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    config_json text NOT NULL,
    business_json text,
    project_json text,
    template_id character varying(64),
    template_version integer,
    t0 timestamp without time zone NOT NULL,
    baseline_start timestamp without time zone,
    baseline_end timestamp without time zone,
    expected_start timestamp without time zone,
    expected_end timestamp without time zone,
    actual_start timestamp without time zone,
    actual_end timestamp without time zone,
    request_key character varying(120),
    request_hash character varying(64),
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    kind character varying(20) DEFAULT 'ORDINARY'::character varying NOT NULL,
    template_node_id text,
    application_id character varying(64),
    planned_start timestamp without time zone,
    authorization_json text,
    schedule_version integer DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_task_instance_kind_ck CHECK (((kind)::text = ANY (ARRAY[('ORDINARY'::character varying)::text, ('PROCESS'::character varying)::text]))),
    CONSTRAINT nocode_task_state_ck CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('RUNNING'::character varying)::text, ('PAUSED'::character varying)::text, ('PENDING_ACCEPTANCE'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);


--
-- Name: COLUMN nocode_task_instance.application_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_task_instance.application_id IS '任务所属应用；独立任务为空，子任务沿根继承';


--
-- Name: COLUMN nocode_task_instance.planned_start; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_task_instance.planned_start IS '显式计划起点；旧任务 T0 继续使用原创建起点';


--
-- Name: COLUMN nocode_task_instance.authorization_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_task_instance.authorization_json IS '服务端批准的总任务数据权限及授权来源快照';


--
-- Name: nocode_task_launch_draft; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_launch_draft (
    id character varying(64) NOT NULL,
    lock_version integer DEFAULT 1 NOT NULL,
    content_json text NOT NULL,
    published_task_id character varying(64),
    publish_key character varying(120),
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT clock_timestamp() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT clock_timestamp() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_nocode_task_launch_draft_publish CHECK ((((published_task_id IS NULL) AND (publish_key IS NULL)) OR ((published_task_id IS NOT NULL) AND (publish_key IS NOT NULL)))),
    CONSTRAINT ck_nocode_task_launch_draft_revision CHECK ((lock_version > 0))
);


--
-- Name: TABLE nocode_task_launch_draft; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_task_launch_draft IS '本人任务编排草稿及原子加入任务池回执';


--
-- Name: nocode_task_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_plan (
    id character varying(64) NOT NULL,
    task_id character varying(64) NOT NULL,
    user_id bigint NOT NULL,
    period character varying(10) NOT NULL,
    plan_date date NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    arranged_by_id bigint NOT NULL,
    source character varying(20) DEFAULT 'SELF'::character varying NOT NULL,
    arranged_at timestamp without time zone NOT NULL,
    end_date date NOT NULL,
    history_reason character varying(80),
    plan_mode character varying(16) DEFAULT 'SCHEDULE'::character varying NOT NULL,
    CONSTRAINT nocode_task_checklist_period_ck CHECK ((((plan_mode)::text <> 'CHECKLIST'::text) OR (((period)::text = 'DAY'::text) AND (end_date = plan_date)) OR (((period)::text = 'WEEK'::text) AND (EXTRACT(isodow FROM plan_date) = (1)::numeric) AND (end_date = (plan_date + 6))))),
    CONSTRAINT nocode_task_plan_mode_ck CHECK (((plan_mode)::text = ANY (ARRAY[('SCHEDULE'::character varying)::text, ('CHECKLIST'::character varying)::text]))),
    CONSTRAINT nocode_task_plan_period_check CHECK (((period)::text = ANY (ARRAY[('DAY'::character varying)::text, ('WEEK'::character varying)::text, ('MONTH'::character varying)::text]))),
    CONSTRAINT nocode_task_plan_range_ck CHECK ((end_date >= plan_date)),
    CONSTRAINT nocode_task_plan_source_ck CHECK (((source)::text = ANY (ARRAY[('SELF'::character varying)::text, ('MANAGER'::character varying)::text])))
);


--
-- Name: nocode_task_record_link; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_record_link (
    id character varying(64) NOT NULL,
    task_id character varying(64) NOT NULL,
    application_id character varying(64) NOT NULL,
    object_id character varying(64) NOT NULL,
    record_id character varying(200) NOT NULL,
    label character varying(200),
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: nocode_task_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_template (
    id character varying(64) NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    lock_version integer DEFAULT 0 NOT NULL,
    published_version integer,
    nodes_json text NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    kind character varying(20) DEFAULT 'ORDINARY'::character varying NOT NULL,
    root_json text,
    authorization_json text,
    primary_version integer,
    CONSTRAINT nocode_task_template_kind_ck CHECK (((kind)::text = ANY (ARRAY[('ORDINARY'::character varying)::text, ('PROCESS'::character varying)::text]))),
    CONSTRAINT nocode_task_template_primary_version_ck CHECK (((primary_version IS NULL) OR ((published_version IS NOT NULL) AND ((primary_version >= 1) AND (primary_version <= published_version)))))
);


--
-- Name: COLUMN nocode_task_template.published_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_task_template.published_version IS '最新发布序号，仅随新版本发布递增';


--
-- Name: COLUMN nocode_task_template.primary_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_task_template.primary_version IS '默认发起版本；切换不修改模板草稿和已有实例';


--
-- Name: nocode_task_template_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_task_template_version (
    id character varying(64) NOT NULL,
    template_id character varying(64) NOT NULL,
    version_no integer NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    nodes_json text NOT NULL,
    bindings_json text NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    kind character varying(20) DEFAULT 'ORDINARY'::character varying NOT NULL,
    root_json text,
    authorization_json text,
    CONSTRAINT nocode_task_template_version_kind_ck CHECK (((kind)::text = ANY (ARRAY[('ORDINARY'::character varying)::text, ('PROCESS'::character varying)::text])))
);


--
-- Name: COLUMN nocode_task_template_version.authorization_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_task_template_version.authorization_json IS '模板发布者批准的不可扩权授权快照';


--
-- Name: nocode_work_draft; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_work_draft (
    id character varying(36) NOT NULL,
    source_type character varying(32) NOT NULL,
    source_id character varying(128) NOT NULL,
    state character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    lock_version integer DEFAULT 0 NOT NULL,
    resource_json jsonb NOT NULL,
    object_id character varying(128) NOT NULL,
    record_id character varying(512),
    base_record_revision character varying(256),
    values_json jsonb NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    details_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    related_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT ck_nocode_work_draft_related_object CHECK ((jsonb_typeof(related_json) = 'object'::text)),
    CONSTRAINT nocode_work_draft_deleted_ck CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_work_draft_revision_ck CHECK ((lock_version >= 0)),
    CONSTRAINT nocode_work_draft_state_ck CHECK (((state)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('SUBMITTED'::character varying)::text]))),
    CONSTRAINT nocode_work_draft_values_ck CHECK ((jsonb_typeof(values_json) = 'object'::text))
);


--
-- Name: TABLE nocode_work_draft; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_work_draft IS '公共工作草稿；来源资格与实时业务授权由服务层验证';


--
-- Name: COLUMN nocode_work_draft.details_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_work_draft.details_json IS '主单据的完整明细输入集合，含稳定临时行键、原记录 ID 及修订';


--
-- Name: COLUMN nocode_work_draft.related_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.nocode_work_draft.related_json IS '独立关联表单的暂存输入，按发布关联区域标识分组';


--
-- Name: nocode_work_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_work_event (
    id character varying(36) NOT NULL,
    event_type character varying(32) NOT NULL,
    source_type character varying(32) NOT NULL,
    source_id character varying(128) NOT NULL,
    submission_id character varying(36) NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_work_event_deleted_ck CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE nocode_work_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_work_event IS '与业务提交同事务的事件事实；当前仅登记，后续接入可靠投递';


--
-- Name: nocode_work_submission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_work_submission (
    id character varying(36) NOT NULL,
    draft_id character varying(36) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_digest character varying(64) NOT NULL,
    material_json jsonb NOT NULL,
    creator character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    updater character varying(64) NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    CONSTRAINT nocode_work_submission_deleted_ck CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT nocode_work_submission_material_ck CHECK ((jsonb_typeof(material_json) = 'object'::text))
);


--
-- Name: TABLE nocode_work_submission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_work_submission IS '不可变提交材料；业务服务不提供覆写历史内容接口';


--
-- Name: nocode_workflow_task_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nocode_workflow_task_node (
    id character varying(64) NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL,
    execution_id character varying(64) NOT NULL,
    process_instance_id character varying(64) NOT NULL,
    process_definition_id character varying(128) NOT NULL,
    node_id character varying(200) NOT NULL,
    node_name character varying(255) NOT NULL,
    initiator_id bigint NOT NULL,
    publisher_id bigint NOT NULL,
    configuration_json text NOT NULL,
    people_json text NOT NULL,
    task_id character varying(64),
    state character varying(32) DEFAULT 'CREATING'::character varying NOT NULL,
    last_error character varying(1000),
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT nocode_workflow_task_node_state_ck CHECK (((state)::text = ANY (ARRAY[('CREATING'::character varying)::text, ('WAITING'::character varying)::text, ('COMPLETED'::character varying)::text, ('INVALIDATED'::character varying)::text])))
);


--
-- Name: TABLE nocode_workflow_task_node; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.nocode_workflow_task_node IS '工作流任务节点激活与完成交接；失败重试不重复创建或推进';


--
-- Name: project_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_menu (
    id bigint NOT NULL,
    name character varying(50) NOT NULL,
    path character varying(200),
    component character varying(200),
    icon character varying(50),
    parent_id character varying(64),
    sort integer,
    menu_type smallint,
    project_mode character varying(30),
    menu_group character varying(30),
    badge_text character varying(20),
    badge_warn smallint,
    optional smallint,
    status smallint,
    create_time timestamp(6) without time zone NOT NULL,
    update_time timestamp(6) without time zone NOT NULL,
    deleted smallint DEFAULT 0,
    creator character varying(64),
    updater character varying(64),
    visible_roles character varying(500) DEFAULT NULL::character varying,
    code character varying(255) NOT NULL,
    create_by character varying(64),
    update_by character varying(64)
);


--
-- Name: COLUMN project_menu.visible_roles; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.project_menu.visible_roles IS '可见角色：逗号分隔的角色编码，NULL=全部可见';


--
-- Name: project_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_role (
    id bigint NOT NULL,
    project_id bigint,
    code character varying(64) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    is_system smallint DEFAULT 0,
    inherits_from character varying(64) DEFAULT NULL::character varying,
    sort_order integer DEFAULT 0,
    creator character varying(64),
    updater character varying(64),
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: qrtz_blob_triggers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_blob_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    blob_data bytea
);


--
-- Name: qrtz_calendars; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_calendars (
    sched_name character varying(120) NOT NULL,
    calendar_name character varying(200) NOT NULL,
    calendar bytea NOT NULL
);


--
-- Name: qrtz_cron_triggers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_cron_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    cron_expression character varying(120) NOT NULL,
    time_zone_id character varying(80)
);


--
-- Name: qrtz_fired_triggers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_fired_triggers (
    sched_name character varying(120) NOT NULL,
    entry_id character varying(95) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    instance_name character varying(200) NOT NULL,
    fired_time bigint NOT NULL,
    sched_time bigint NOT NULL,
    priority integer NOT NULL,
    state character varying(16) NOT NULL,
    job_name character varying(200),
    job_group character varying(200),
    is_nonconcurrent boolean,
    requests_recovery boolean
);


--
-- Name: qrtz_job_details; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_job_details (
    sched_name character varying(120) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    job_class_name character varying(250) NOT NULL,
    is_durable boolean NOT NULL,
    is_nonconcurrent boolean NOT NULL,
    is_update_data boolean NOT NULL,
    requests_recovery boolean NOT NULL,
    job_data bytea
);


--
-- Name: qrtz_locks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_locks (
    sched_name character varying(120) NOT NULL,
    lock_name character varying(40) NOT NULL
);


--
-- Name: qrtz_paused_trigger_grps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_paused_trigger_grps (
    sched_name character varying(120) NOT NULL,
    trigger_group character varying(200) NOT NULL
);


--
-- Name: qrtz_scheduler_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_scheduler_state (
    sched_name character varying(120) NOT NULL,
    instance_name character varying(200) NOT NULL,
    last_checkin_time bigint NOT NULL,
    checkin_interval bigint NOT NULL
);


--
-- Name: qrtz_simple_triggers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_simple_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    repeat_count bigint NOT NULL,
    repeat_interval bigint NOT NULL,
    times_triggered bigint NOT NULL
);


--
-- Name: qrtz_simprop_triggers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_simprop_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    str_prop_1 character varying(512),
    str_prop_2 character varying(512),
    str_prop_3 character varying(512),
    int_prop_1 integer,
    int_prop_2 integer,
    long_prop_1 bigint,
    long_prop_2 bigint,
    dec_prop_1 numeric(13,4),
    dec_prop_2 numeric(13,4),
    bool_prop_1 boolean,
    bool_prop_2 boolean
);


--
-- Name: qrtz_triggers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.qrtz_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    next_fire_time bigint,
    prev_fire_time bigint,
    priority integer,
    trigger_state character varying(16) NOT NULL,
    trigger_type character varying(8) NOT NULL,
    start_time bigint NOT NULL,
    end_time bigint,
    calendar_name character varying(200),
    misfire_instr smallint,
    job_data bytea
);


--
-- Name: repo_package; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.repo_package (
    id smallint NOT NULL,
    name character varying(200) NOT NULL,
    group_id character varying(200),
    version character varying(100) NOT NULL,
    repo_type character varying(20) NOT NULL,
    description text,
    usage_guide text,
    file_path character varying(500),
    nexus_url character varying(500),
    size bigint,
    checksum character varying(64),
    source character varying(50),
    status smallint,
    creator character varying(32),
    create_time timestamp(6) without time zone,
    updater character varying(32),
    update_time timestamp(6) without time zone,
    deleted smallint,
    task_id character varying(32),
    upload_status character varying(20),
    error_msg text
);


--
-- Name: TABLE repo_package; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.repo_package IS '依赖包信息表';


--
-- Name: COLUMN repo_package.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.id IS '主键ID';


--
-- Name: COLUMN repo_package.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.name IS '包名称';


--
-- Name: COLUMN repo_package.group_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.group_id IS 'GroupID/Scope (Maven: com.example, NPM: @scope)';


--
-- Name: COLUMN repo_package.version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.version IS '版本号';


--
-- Name: COLUMN repo_package.repo_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.repo_type IS '仓库类型: MAVEN, NPM, PYPI';


--
-- Name: COLUMN repo_package.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.description IS '包描述';


--
-- Name: COLUMN repo_package.usage_guide; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.usage_guide IS '使用/安装指南 (Markdown格式)';


--
-- Name: COLUMN repo_package.file_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.file_path IS '本地文件路径 (上传时临时存储)';


--
-- Name: COLUMN repo_package.nexus_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.nexus_url IS 'Nexus中的URL';


--
-- Name: COLUMN repo_package.size; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.size IS '文件大小(字节)';


--
-- Name: COLUMN repo_package.checksum; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.checksum IS '文件校验和';


--
-- Name: COLUMN repo_package.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.source IS '来源: UPLOAD(本地上传), PROXY(代理拉取)';


--
-- Name: COLUMN repo_package.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.status IS '状态: 0-禁用, 1-启用';


--
-- Name: COLUMN repo_package.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.creator IS '创建人ID';


--
-- Name: COLUMN repo_package.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.updater IS '更新人ID';


--
-- Name: COLUMN repo_package.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.deleted IS '逻辑删除';


--
-- Name: COLUMN repo_package.task_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.task_id IS '关联的上传任务ID';


--
-- Name: COLUMN repo_package.upload_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.upload_status IS '上传状态: PENDING, SUCCESS, FAILED';


--
-- Name: COLUMN repo_package.error_msg; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_package.error_msg IS '上传错误信息';


--
-- Name: repo_upload_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.repo_upload_task (
    id smallint NOT NULL,
    package_id character varying(32),
    task_type character varying(20) NOT NULL,
    file_name character varying(300) NOT NULL,
    source_path character varying(500),
    target_repo character varying(100) NOT NULL,
    status character varying(20),
    progress integer,
    error_msg text,
    retry_count integer,
    create_time timestamp(6) without time zone,
    creator character varying(32),
    update_time timestamp(6) without time zone,
    updater character varying(32),
    deleted smallint DEFAULT 0 NOT NULL,
    parent_id character varying(32),
    sub_task_count integer,
    extracted_path character varying(500),
    repo_type character varying(20),
    package_count integer,
    success_count integer,
    failed_count integer
);


--
-- Name: TABLE repo_upload_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.repo_upload_task IS '上传任务表';


--
-- Name: COLUMN repo_upload_task.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.id IS '主键ID';


--
-- Name: COLUMN repo_upload_task.package_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.package_id IS '关联的包ID';


--
-- Name: COLUMN repo_upload_task.task_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.task_type IS '任务类型: UPLOAD(上传), SYNC(同步)';


--
-- Name: COLUMN repo_upload_task.file_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.file_name IS '文件名';


--
-- Name: COLUMN repo_upload_task.source_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.source_path IS '源文件路径';


--
-- Name: COLUMN repo_upload_task.target_repo; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.target_repo IS '目标Nexus仓库名';


--
-- Name: COLUMN repo_upload_task.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.status IS '状态: PENDING, PROCESSING, SUCCESS, FAILED';


--
-- Name: COLUMN repo_upload_task.progress; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.progress IS '进度百分比';


--
-- Name: COLUMN repo_upload_task.error_msg; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.error_msg IS '错误信息';


--
-- Name: COLUMN repo_upload_task.retry_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.retry_count IS '重试次数';


--
-- Name: COLUMN repo_upload_task.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.creator IS '创建人ID';


--
-- Name: COLUMN repo_upload_task.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.updater IS '更新人ID';


--
-- Name: COLUMN repo_upload_task.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.parent_id IS '父任务ID';


--
-- Name: COLUMN repo_upload_task.sub_task_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.sub_task_count IS '子任务数量';


--
-- Name: COLUMN repo_upload_task.extracted_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.extracted_path IS '解压后的目录路径';


--
-- Name: COLUMN repo_upload_task.repo_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.repo_type IS '仓库类型';


--
-- Name: COLUMN repo_upload_task.package_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.package_count IS '包总数';


--
-- Name: COLUMN repo_upload_task.success_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.success_count IS '成功数量';


--
-- Name: COLUMN repo_upload_task.failed_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.repo_upload_task.failed_count IS '失败数量';


--
-- Name: scaffold_component; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scaffold_component (
    id bigint NOT NULL,
    description text,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    create_time timestamp(6) without time zone,
    update_time timestamp(6) without time zone,
    deleted smallint DEFAULT 0 NOT NULL,
    version_id bigint NOT NULL,
    component_key character varying(100) NOT NULL,
    display_name character varying(200) NOT NULL,
    version_tag character varying(50),
    target_sub_project character varying(100) NOT NULL,
    snippet text NOT NULL,
    config_items jsonb DEFAULT '[]'::jsonb NOT NULL,
    deprecation_reason text,
    change_status character varying(20) DEFAULT 'unchanged'::character varying NOT NULL,
    creator character varying(32),
    updater character varying(32),
    runtime_snippet text
);


--
-- Name: TABLE scaffold_component; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.scaffold_component IS '底板版本级组件配置表，定义底板各版本可选的组件';


--
-- Name: COLUMN scaffold_component.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.description IS '组件描述';


--
-- Name: COLUMN scaffold_component.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.status IS '状态：active-可用 / deprecated-已废弃';


--
-- Name: COLUMN scaffold_component.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.deleted IS '逻辑删除：0-未删除 1-已删除';


--
-- Name: COLUMN scaffold_component.version_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.version_id IS '关联 scaffold_version.id';


--
-- Name: COLUMN scaffold_component.component_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.component_key IS '组件唯一标识，如 minio-storage';


--
-- Name: COLUMN scaffold_component.display_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.display_name IS '组件展示名称，如 MinIO 对象存储';


--
-- Name: COLUMN scaffold_component.version_tag; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.version_tag IS '组件版本号，独立于底板版本';


--
-- Name: COLUMN scaffold_component.target_sub_project; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.target_sub_project IS '目标子工程名，关联 sub_projects JSONB 中的 name 字段';


--
-- Name: COLUMN scaffold_component.snippet; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.snippet IS '引入代码片段，如 Maven依赖/npm包/pip install';


--
-- Name: COLUMN scaffold_component.config_items; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.config_items IS '运行时配置项JSONB数组，结构包含key/label/type/required/placeholder';


--
-- Name: COLUMN scaffold_component.deprecation_reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.deprecation_reason IS '废弃原因';


--
-- Name: COLUMN scaffold_component.change_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.change_status IS '相对于上一版本的变更状态：added-新增/modified-修改/unchanged-无变化';


--
-- Name: COLUMN scaffold_component.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.creator IS '创建人ID';


--
-- Name: COLUMN scaffold_component.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_component.updater IS '更新人ID';


--
-- Name: scaffold_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scaffold_template (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    language character varying(50) NOT NULL,
    readme text,
    uploaded_by_type character varying(20) DEFAULT 'ops'::character varying NOT NULL,
    org_id character varying(100),
    uploaded_by character varying(100) NOT NULL,
    create_time timestamp(6) without time zone,
    creator character varying(32),
    update_time timestamp(6) without time zone,
    updater character varying(32),
    deleted smallint DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL
);


--
-- Name: TABLE scaffold_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.scaffold_template IS '脚手架底板主表';


--
-- Name: COLUMN scaffold_template.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.name IS '底板名称';


--
-- Name: COLUMN scaffold_template.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.description IS '底板描述';


--
-- Name: COLUMN scaffold_template.language; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.language IS '开发语言枚举：java、python、cpp、vue3';


--
-- Name: COLUMN scaffold_template.readme; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.readme IS '从上传的底板Zip包内README.md自动提取的文本内容';


--
-- Name: COLUMN scaffold_template.uploaded_by_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.uploaded_by_type IS '上传者类型：ops-运维管理员（跨租户可见），tenant_admin-租户管理员（仅本租户可见）';


--
-- Name: COLUMN scaffold_template.org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.org_id IS '租户ID（租户管理员上传时有值，ops上传时为NULL）';


--
-- Name: COLUMN scaffold_template.uploaded_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.uploaded_by IS '上传者姓名';


--
-- Name: COLUMN scaffold_template.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.creator IS '创建人ID';


--
-- Name: COLUMN scaffold_template.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.updater IS '更新人ID';


--
-- Name: COLUMN scaffold_template.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_template.deleted IS '逻辑删除：0-未删除 1-已删除';


--
-- Name: scaffold_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scaffold_version (
    id bigint NOT NULL,
    scaffold_id bigint NOT NULL,
    create_time timestamp(6) without time zone,
    update_time timestamp(6) without time zone,
    deleted smallint DEFAULT 0 NOT NULL,
    version_tag character varying(50) NOT NULL,
    zip_file_path character varying(500),
    zip_inherited_from character varying(100),
    sub_projects jsonb NOT NULL,
    change_summary text,
    creator character varying(32),
    updater character varying(32),
    readme_path character varying(500)
);


--
-- Name: TABLE scaffold_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.scaffold_version IS '底板版本表，每个底板可有多个版本';


--
-- Name: COLUMN scaffold_version.scaffold_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.scaffold_id IS '关联 scaffold_template.id';


--
-- Name: COLUMN scaffold_version.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.deleted IS '逻辑删除：0-未删除 1-已删除';


--
-- Name: COLUMN scaffold_version.version_tag; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.version_tag IS '版本号：如 1.0.0、2.1.0';


--
-- Name: COLUMN scaffold_version.zip_file_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.zip_file_path IS 'MinIO存储路径，为NULL时表示从zip_inherited_from版本继承模板文件';


--
-- Name: COLUMN scaffold_version.zip_inherited_from; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.zip_inherited_from IS '继承来源版本ID，仅zip_file_path为NULL时有值';


--
-- Name: COLUMN scaffold_version.sub_projects; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.sub_projects IS '子工程配置JSONB数组，结构包含name/language/build_file_path/config_file_path/sql_directory_path/readme_path';


--
-- Name: COLUMN scaffold_version.change_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.change_summary IS '版本变更说明';


--
-- Name: COLUMN scaffold_version.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.creator IS '创建人ID';


--
-- Name: COLUMN scaffold_version.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.scaffold_version.updater IS '更新人ID';


--
-- Name: sys_attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_attachment (
    id character varying(32) NOT NULL,
    file_name character varying(255) NOT NULL,
    file_path character varying(500) NOT NULL,
    file_size bigint,
    file_type character varying(50),
    storage_type smallint,
    business_type character varying(50),
    business_id character varying(32),
    tenant_id character varying(32) NOT NULL,
    create_by character varying(32),
    update_by character varying(32),
    create_time timestamp(6) without time zone,
    update_time timestamp(6) without time zone,
    deleted smallint
);


--
-- Name: TABLE sys_attachment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_attachment IS '系统附件表';


--
-- Name: COLUMN sys_attachment.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.id IS '主键ID';


--
-- Name: COLUMN sys_attachment.file_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.file_name IS '原始文件名';


--
-- Name: COLUMN sys_attachment.file_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.file_path IS '存储路径';


--
-- Name: COLUMN sys_attachment.file_size; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.file_size IS '文件大小(字节)';


--
-- Name: COLUMN sys_attachment.file_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.file_type IS '文件MIME类型';


--
-- Name: COLUMN sys_attachment.storage_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.storage_type IS '存储类型:1-本地 2-OSS 3-MinIO';


--
-- Name: COLUMN sys_attachment.business_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.business_type IS '业务类型:message/scaffold/template等';


--
-- Name: COLUMN sys_attachment.business_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.business_id IS '关联业务ID';


--
-- Name: COLUMN sys_attachment.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.tenant_id IS '租户ID';


--
-- Name: COLUMN sys_attachment.create_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.create_by IS '上传人ID';


--
-- Name: COLUMN sys_attachment.update_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.update_by IS '更新人ID';


--
-- Name: COLUMN sys_attachment.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_attachment.update_time IS '更新时间';


--
-- Name: sys_msg; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_msg (
    id bigint NOT NULL,
    msg_template_id bigint NOT NULL,
    title character varying(500),
    content text,
    url text,
    source_type character varying(64),
    source_id character varying(64),
    owner_id character varying(64),
    owner_name character varying(64),
    msg_data text,
    properties text,
    priority integer,
    send_time timestamp(6) without time zone,
    status character varying(32),
    creator character varying(64) NOT NULL,
    create_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    updater character varying(64),
    update_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: COLUMN sys_msg.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg.status IS '待发送，发送中，撤销';


--
-- Name: COLUMN sys_msg.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg.creator IS '创建人';


--
-- Name: COLUMN sys_msg.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg.create_time IS '创建时间';


--
-- Name: COLUMN sys_msg.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg.updater IS '最后更新人';


--
-- Name: COLUMN sys_msg.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg.update_time IS '最后更新时间';


--
-- Name: COLUMN sys_msg.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg.deleted IS '软删除：0=正常 1=已删除';


--
-- Name: sys_msg_notice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_msg_notice (
    id bigint NOT NULL,
    msg_id bigint,
    receiver_type character varying(64),
    receiver_id character varying(64),
    receiver_name character varying(64),
    status character varying(32),
    notice_channel character varying(64),
    notice_time timestamp(6) without time zone,
    notice_data text,
    has_read smallint,
    read_time timestamp(6) without time zone
);


--
-- Name: COLUMN sys_msg_notice.notice_data; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_notice.notice_data IS 'JSON';


--
-- Name: sys_msg_notice_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_msg_notice_log (
    id bigint NOT NULL,
    notice_id bigint NOT NULL,
    notice_channel character varying(64) NOT NULL,
    channel_info text,
    log_info text,
    log_time timestamp(6) without time zone NOT NULL
);


--
-- Name: sys_msg_subscribe; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_msg_subscribe (
    id bigint NOT NULL,
    msg_template_id bigint,
    user_id character varying(64),
    user_name character varying(64),
    properties text,
    creator character varying(64) NOT NULL,
    create_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    updater character varying(64),
    update_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: COLUMN sys_msg_subscribe.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_subscribe.creator IS '创建人';


--
-- Name: COLUMN sys_msg_subscribe.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_subscribe.create_time IS '创建时间';


--
-- Name: COLUMN sys_msg_subscribe.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_subscribe.updater IS '最后更新人';


--
-- Name: COLUMN sys_msg_subscribe.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_subscribe.update_time IS '最后更新时间';


--
-- Name: COLUMN sys_msg_subscribe.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_subscribe.deleted IS '软删除：0=正常 1=已删除';


--
-- Name: sys_msg_targets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_msg_targets (
    id bigint NOT NULL,
    msg_id bigint NOT NULL,
    target_type character varying(64) NOT NULL,
    target_id character varying(64),
    target_name character varying(64)
);


--
-- Name: sys_msg_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_msg_template (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(64) NOT NULL,
    priority integer,
    meta_data text,
    properties text,
    subscribe_able integer,
    template_title text,
    template_content text,
    template_url text,
    notice_config text,
    creator character varying(64) NOT NULL,
    create_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    updater character varying(64),
    update_time timestamp(6) without time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: COLUMN sys_msg_template.priority; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.priority IS '越小优先级越高';


--
-- Name: COLUMN sys_msg_template.meta_data; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.meta_data IS 'JSON';


--
-- Name: COLUMN sys_msg_template.subscribe_able; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.subscribe_able IS '默认不可订阅';


--
-- Name: COLUMN sys_msg_template.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.creator IS '创建人';


--
-- Name: COLUMN sys_msg_template.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.create_time IS '创建时间';


--
-- Name: COLUMN sys_msg_template.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.updater IS '最后更新人';


--
-- Name: COLUMN sys_msg_template.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.update_time IS '最后更新时间';


--
-- Name: COLUMN sys_msg_template.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_msg_template.deleted IS '软删除：0=正常 1=已删除';


--
-- Name: sys_msg_template_target; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_msg_template_target (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    msg_template_id bigint NOT NULL,
    target_type character varying(20) NOT NULL,
    target_id character varying(64) NOT NULL,
    target_name character varying(100) NOT NULL,
    creator character varying(64),
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64),
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: system_dept; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_dept (
    id bigint NOT NULL,
    name character varying(30) DEFAULT ''::character varying NOT NULL,
    parent_id bigint DEFAULT 0 NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    leader_user_id bigint,
    phone character varying(11) DEFAULT NULL::character varying,
    email character varying(50) DEFAULT NULL::character varying,
    status smallint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL,
    org_id bigint,
    dept_code character varying(255) NOT NULL
);


--
-- Name: TABLE system_dept; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_dept IS '部门表';


--
-- Name: COLUMN system_dept.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.id IS '部门id';


--
-- Name: COLUMN system_dept.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.name IS '部门名称';


--
-- Name: COLUMN system_dept.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.parent_id IS '父部门id';


--
-- Name: COLUMN system_dept.sort; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.sort IS '显示顺序';


--
-- Name: COLUMN system_dept.leader_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.leader_user_id IS '负责人';


--
-- Name: COLUMN system_dept.phone; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.phone IS '联系电话';


--
-- Name: COLUMN system_dept.email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.email IS '邮箱';


--
-- Name: COLUMN system_dept.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.status IS '部门状态（0正常 1停用）';


--
-- Name: COLUMN system_dept.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.creator IS '创建者';


--
-- Name: COLUMN system_dept.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.create_time IS '创建时间';


--
-- Name: COLUMN system_dept.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.updater IS '更新者';


--
-- Name: COLUMN system_dept.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.update_time IS '更新时间';


--
-- Name: COLUMN system_dept.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.deleted IS '是否删除';


--
-- Name: COLUMN system_dept.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.tenant_id IS '租户编号';


--
-- Name: COLUMN system_dept.org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.org_id IS '组织id';


--
-- Name: COLUMN system_dept.dept_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dept.dept_code IS '部门编码';


--
-- Name: system_dept_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_dept_seq
    START WITH 118
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_dict_data; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_dict_data (
    id bigint NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    label character varying(100) DEFAULT ''::character varying NOT NULL,
    value character varying(100) DEFAULT ''::character varying NOT NULL,
    dict_type character varying(100) DEFAULT ''::character varying NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    color_type character varying(100) DEFAULT ''::character varying,
    css_class character varying(100) DEFAULT ''::character varying,
    remark character varying(500) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_dict_data; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_dict_data IS '字典数据表';


--
-- Name: COLUMN system_dict_data.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.id IS '字典编码';


--
-- Name: COLUMN system_dict_data.sort; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.sort IS '字典排序';


--
-- Name: COLUMN system_dict_data.label; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.label IS '字典标签';


--
-- Name: COLUMN system_dict_data.value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.value IS '字典键值';


--
-- Name: COLUMN system_dict_data.dict_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.dict_type IS '字典类型';


--
-- Name: COLUMN system_dict_data.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.status IS '状态（0正常 1停用）';


--
-- Name: COLUMN system_dict_data.color_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.color_type IS '颜色类型';


--
-- Name: COLUMN system_dict_data.css_class; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.css_class IS 'css 样式';


--
-- Name: COLUMN system_dict_data.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.remark IS '备注';


--
-- Name: COLUMN system_dict_data.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.creator IS '创建者';


--
-- Name: COLUMN system_dict_data.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.create_time IS '创建时间';


--
-- Name: COLUMN system_dict_data.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.updater IS '更新者';


--
-- Name: COLUMN system_dict_data.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.update_time IS '更新时间';


--
-- Name: COLUMN system_dict_data.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_data.deleted IS '是否删除';


--
-- Name: system_dict_data_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_dict_data_seq
    START WITH 3449
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_dict_type; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_dict_type (
    id bigint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    type character varying(100) DEFAULT ''::character varying NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    remark character varying(500) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    deleted_time timestamp(6) without time zone
);


--
-- Name: TABLE system_dict_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_dict_type IS '字典类型表';


--
-- Name: COLUMN system_dict_type.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.id IS '字典主键';


--
-- Name: COLUMN system_dict_type.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.name IS '字典名称';


--
-- Name: COLUMN system_dict_type.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.type IS '字典类型';


--
-- Name: COLUMN system_dict_type.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.status IS '状态（0正常 1停用）';


--
-- Name: COLUMN system_dict_type.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.remark IS '备注';


--
-- Name: COLUMN system_dict_type.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.creator IS '创建者';


--
-- Name: COLUMN system_dict_type.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.create_time IS '创建时间';


--
-- Name: COLUMN system_dict_type.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.updater IS '更新者';


--
-- Name: COLUMN system_dict_type.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.update_time IS '更新时间';


--
-- Name: COLUMN system_dict_type.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.deleted IS '是否删除';


--
-- Name: COLUMN system_dict_type.deleted_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_dict_type.deleted_time IS '删除时间';


--
-- Name: system_dict_type_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_dict_type_seq
    START WITH 2139
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_feedback (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    feedback_type character varying(20) NOT NULL,
    title character varying(100) NOT NULL,
    description text NOT NULL,
    submitter_id bigint NOT NULL,
    submitter_name character varying(100) NOT NULL,
    page_path character varying(1000),
    page_title character varying(200),
    build_commit character varying(100),
    user_agent character varying(500),
    image_file_ids text,
    message_id bigint,
    creator character varying(64),
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64),
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    feedback_no character varying(64) NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    assignee_id bigint,
    assignee_name character varying(100),
    submitter_role_names text,
    project_id bigint,
    project_name character varying(200),
    version integer DEFAULT 0 NOT NULL
);


--
-- Name: system_feedback_follow_up; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_feedback_follow_up (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    feedback_id bigint NOT NULL,
    action_type character varying(32) NOT NULL,
    from_status character varying(32),
    to_status character varying(32),
    from_assignee_id bigint,
    to_assignee_id bigint,
    operator_id bigint NOT NULL,
    operator_name character varying(100) NOT NULL,
    content text,
    creator character varying(64),
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64),
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: system_feedback_reference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_feedback_reference (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    feedback_id bigint NOT NULL,
    project_id bigint NOT NULL,
    project_name character varying(200),
    reference_type character varying(20) NOT NULL,
    target_id bigint NOT NULL,
    target_code character varying(100),
    target_title character varying(200) NOT NULL,
    creator character varying(64),
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64),
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: system_login_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_login_log (
    id bigint NOT NULL,
    log_type bigint NOT NULL,
    trace_id character varying(64) DEFAULT ''::character varying NOT NULL,
    user_id bigint DEFAULT 0 NOT NULL,
    user_type smallint DEFAULT 0 NOT NULL,
    username character varying(50) DEFAULT ''::character varying NOT NULL,
    result smallint NOT NULL,
    user_ip character varying(50) NOT NULL,
    user_agent character varying(512) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_login_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_login_log IS '系统访问记录';


--
-- Name: COLUMN system_login_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.id IS '访问ID';


--
-- Name: COLUMN system_login_log.log_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.log_type IS '日志类型';


--
-- Name: COLUMN system_login_log.trace_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.trace_id IS '链路追踪编号';


--
-- Name: COLUMN system_login_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.user_id IS '用户编号';


--
-- Name: COLUMN system_login_log.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.user_type IS '用户类型';


--
-- Name: COLUMN system_login_log.username; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.username IS '用户账号';


--
-- Name: COLUMN system_login_log.result; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.result IS '登陆结果';


--
-- Name: COLUMN system_login_log.user_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.user_ip IS '用户 IP';


--
-- Name: COLUMN system_login_log.user_agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.user_agent IS '浏览器 UA';


--
-- Name: COLUMN system_login_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.creator IS '创建者';


--
-- Name: COLUMN system_login_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.create_time IS '创建时间';


--
-- Name: COLUMN system_login_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.updater IS '更新者';


--
-- Name: COLUMN system_login_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.update_time IS '更新时间';


--
-- Name: COLUMN system_login_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.deleted IS '是否删除';


--
-- Name: COLUMN system_login_log.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_login_log.tenant_id IS '租户编号';


--
-- Name: system_login_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_login_log_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_mail_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_mail_account (
    id bigint NOT NULL,
    mail character varying(255) NOT NULL,
    username character varying(255) NOT NULL,
    password character varying(255) NOT NULL,
    host character varying(255) NOT NULL,
    port integer NOT NULL,
    ssl_enable boolean DEFAULT false NOT NULL,
    starttls_enable boolean DEFAULT false NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_mail_account; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_mail_account IS '邮箱账号表';


--
-- Name: COLUMN system_mail_account.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.id IS '主键';


--
-- Name: COLUMN system_mail_account.mail; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.mail IS '邮箱';


--
-- Name: COLUMN system_mail_account.username; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.username IS '用户名';


--
-- Name: COLUMN system_mail_account.password; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.password IS '密码';


--
-- Name: COLUMN system_mail_account.host; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.host IS 'SMTP 服务器域名';


--
-- Name: COLUMN system_mail_account.port; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.port IS 'SMTP 服务器端口';


--
-- Name: COLUMN system_mail_account.ssl_enable; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.ssl_enable IS '是否开启 SSL';


--
-- Name: COLUMN system_mail_account.starttls_enable; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.starttls_enable IS '是否开启 STARTTLS';


--
-- Name: COLUMN system_mail_account.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.creator IS '创建者';


--
-- Name: COLUMN system_mail_account.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.create_time IS '创建时间';


--
-- Name: COLUMN system_mail_account.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.updater IS '更新者';


--
-- Name: COLUMN system_mail_account.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.update_time IS '更新时间';


--
-- Name: COLUMN system_mail_account.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_account.deleted IS '是否删除';


--
-- Name: system_mail_account_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_mail_account_seq
    START WITH 5
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_mail_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_mail_log (
    id bigint NOT NULL,
    user_id bigint,
    user_type smallint,
    to_mails character varying(1024) NOT NULL,
    cc_mails character varying(1024) DEFAULT NULL::character varying,
    bcc_mails character varying(1024) DEFAULT NULL::character varying,
    account_id bigint NOT NULL,
    from_mail character varying(255) NOT NULL,
    template_id bigint NOT NULL,
    template_code character varying(63) NOT NULL,
    template_nickname character varying(255) DEFAULT NULL::character varying,
    template_title character varying(255) NOT NULL,
    template_content text NOT NULL,
    template_params character varying(255) NOT NULL,
    send_status smallint DEFAULT 0 NOT NULL,
    send_time timestamp(6) without time zone,
    send_message_id character varying(255) DEFAULT NULL::character varying,
    send_exception character varying(4096) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_mail_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_mail_log IS '邮件日志表';


--
-- Name: COLUMN system_mail_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.id IS '编号';


--
-- Name: COLUMN system_mail_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.user_id IS '用户编号';


--
-- Name: COLUMN system_mail_log.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.user_type IS '用户类型';


--
-- Name: COLUMN system_mail_log.to_mails; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.to_mails IS '接收邮箱地址';


--
-- Name: COLUMN system_mail_log.cc_mails; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.cc_mails IS '抄送邮箱地址';


--
-- Name: COLUMN system_mail_log.bcc_mails; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.bcc_mails IS '密送邮箱地址';


--
-- Name: COLUMN system_mail_log.account_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.account_id IS '邮箱账号编号';


--
-- Name: COLUMN system_mail_log.from_mail; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.from_mail IS '发送邮箱地址';


--
-- Name: COLUMN system_mail_log.template_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.template_id IS '模板编号';


--
-- Name: COLUMN system_mail_log.template_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.template_code IS '模板编码';


--
-- Name: COLUMN system_mail_log.template_nickname; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.template_nickname IS '模版发送人名称';


--
-- Name: COLUMN system_mail_log.template_title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.template_title IS '邮件标题';


--
-- Name: COLUMN system_mail_log.template_content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.template_content IS '邮件内容';


--
-- Name: COLUMN system_mail_log.template_params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.template_params IS '邮件参数';


--
-- Name: COLUMN system_mail_log.send_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.send_status IS '发送状态';


--
-- Name: COLUMN system_mail_log.send_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.send_time IS '发送时间';


--
-- Name: COLUMN system_mail_log.send_message_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.send_message_id IS '发送返回的消息 ID';


--
-- Name: COLUMN system_mail_log.send_exception; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.send_exception IS '发送异常';


--
-- Name: COLUMN system_mail_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.creator IS '创建者';


--
-- Name: COLUMN system_mail_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.create_time IS '创建时间';


--
-- Name: COLUMN system_mail_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.updater IS '更新者';


--
-- Name: COLUMN system_mail_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.update_time IS '更新时间';


--
-- Name: COLUMN system_mail_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_log.deleted IS '是否删除';


--
-- Name: system_mail_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_mail_log_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_mail_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_mail_template (
    id bigint NOT NULL,
    name character varying(63) NOT NULL,
    code character varying(63) NOT NULL,
    account_id bigint NOT NULL,
    nickname character varying(255) DEFAULT NULL::character varying,
    title character varying(255) NOT NULL,
    content character varying(10240) NOT NULL,
    params character varying(255) NOT NULL,
    status smallint NOT NULL,
    remark character varying(255) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_mail_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_mail_template IS '邮件模版表';


--
-- Name: COLUMN system_mail_template.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.id IS '编号';


--
-- Name: COLUMN system_mail_template.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.name IS '模板名称';


--
-- Name: COLUMN system_mail_template.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.code IS '模板编码';


--
-- Name: COLUMN system_mail_template.account_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.account_id IS '发送的邮箱账号编号';


--
-- Name: COLUMN system_mail_template.nickname; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.nickname IS '发送人名称';


--
-- Name: COLUMN system_mail_template.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.title IS '模板标题';


--
-- Name: COLUMN system_mail_template.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.content IS '模板内容';


--
-- Name: COLUMN system_mail_template.params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.params IS '参数数组';


--
-- Name: COLUMN system_mail_template.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.status IS '开启状态';


--
-- Name: COLUMN system_mail_template.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.remark IS '备注';


--
-- Name: COLUMN system_mail_template.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.creator IS '创建者';


--
-- Name: COLUMN system_mail_template.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.create_time IS '创建时间';


--
-- Name: COLUMN system_mail_template.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.updater IS '更新者';


--
-- Name: COLUMN system_mail_template.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.update_time IS '更新时间';


--
-- Name: COLUMN system_mail_template.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_mail_template.deleted IS '是否删除';


--
-- Name: system_mail_template_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_mail_template_seq
    START WITH 16
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_menu (
    id bigint NOT NULL,
    name character varying(50) NOT NULL,
    permission character varying(100) DEFAULT ''::character varying NOT NULL,
    type smallint NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    parent_id bigint DEFAULT 0 NOT NULL,
    path character varying(200) DEFAULT ''::character varying,
    icon character varying(100) DEFAULT '#'::character varying,
    component character varying(255) DEFAULT NULL::character varying,
    component_name character varying(255) DEFAULT NULL::character varying,
    status smallint DEFAULT 0 NOT NULL,
    visible boolean DEFAULT true NOT NULL,
    keep_alive boolean DEFAULT true NOT NULL,
    always_show boolean DEFAULT true NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_menu; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_menu IS '菜单权限表';


--
-- Name: COLUMN system_menu.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.id IS '菜单ID';


--
-- Name: COLUMN system_menu.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.name IS '菜单名称';


--
-- Name: COLUMN system_menu.permission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.permission IS '权限标识';


--
-- Name: COLUMN system_menu.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.type IS '菜单类型';


--
-- Name: COLUMN system_menu.sort; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.sort IS '显示顺序';


--
-- Name: COLUMN system_menu.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.parent_id IS '父菜单ID';


--
-- Name: COLUMN system_menu.path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.path IS '路由地址';


--
-- Name: COLUMN system_menu.icon; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.icon IS '菜单图标';


--
-- Name: COLUMN system_menu.component; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.component IS '组件路径';


--
-- Name: COLUMN system_menu.component_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.component_name IS '组件名';


--
-- Name: COLUMN system_menu.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.status IS '菜单状态';


--
-- Name: COLUMN system_menu.visible; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.visible IS '是否可见';


--
-- Name: COLUMN system_menu.keep_alive; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.keep_alive IS '是否缓存';


--
-- Name: COLUMN system_menu.always_show; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.always_show IS '是否总是显示';


--
-- Name: COLUMN system_menu.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.creator IS '创建者';


--
-- Name: COLUMN system_menu.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.create_time IS '创建时间';


--
-- Name: COLUMN system_menu.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.updater IS '更新者';


--
-- Name: COLUMN system_menu.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.update_time IS '更新时间';


--
-- Name: COLUMN system_menu.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_menu.deleted IS '是否删除';


--
-- Name: system_menu_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_menu_seq
    START WITH 5986
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_notice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_notice (
    id bigint NOT NULL,
    title character varying(50) NOT NULL,
    content text NOT NULL,
    type smallint NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_notice; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_notice IS '通知公告表';


--
-- Name: COLUMN system_notice.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.id IS '公告ID';


--
-- Name: COLUMN system_notice.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.title IS '公告标题';


--
-- Name: COLUMN system_notice.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.content IS '公告内容';


--
-- Name: COLUMN system_notice.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.type IS '公告类型（1通知 2公告）';


--
-- Name: COLUMN system_notice.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.status IS '公告状态（0正常 1关闭）';


--
-- Name: COLUMN system_notice.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.creator IS '创建者';


--
-- Name: COLUMN system_notice.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.create_time IS '创建时间';


--
-- Name: COLUMN system_notice.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.updater IS '更新者';


--
-- Name: COLUMN system_notice.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.update_time IS '更新时间';


--
-- Name: COLUMN system_notice.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.deleted IS '是否删除';


--
-- Name: COLUMN system_notice.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notice.tenant_id IS '租户编号';


--
-- Name: system_notice_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_notice_seq
    START WITH 5
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_notify_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_notify_message (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    user_type smallint NOT NULL,
    template_id bigint NOT NULL,
    template_code character varying(64) NOT NULL,
    template_nickname character varying(63) NOT NULL,
    template_content character varying(1024) NOT NULL,
    template_type integer NOT NULL,
    template_params character varying(255) NOT NULL,
    read_status boolean NOT NULL,
    read_time timestamp(6) without time zone,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_notify_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_notify_message IS '站内信消息表';


--
-- Name: COLUMN system_notify_message.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.id IS '用户ID';


--
-- Name: COLUMN system_notify_message.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.user_id IS '用户id';


--
-- Name: COLUMN system_notify_message.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.user_type IS '用户类型';


--
-- Name: COLUMN system_notify_message.template_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.template_id IS '模版编号';


--
-- Name: COLUMN system_notify_message.template_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.template_code IS '模板编码';


--
-- Name: COLUMN system_notify_message.template_nickname; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.template_nickname IS '模版发送人名称';


--
-- Name: COLUMN system_notify_message.template_content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.template_content IS '模版内容';


--
-- Name: COLUMN system_notify_message.template_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.template_type IS '模版类型';


--
-- Name: COLUMN system_notify_message.template_params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.template_params IS '模版参数';


--
-- Name: COLUMN system_notify_message.read_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.read_status IS '是否已读';


--
-- Name: COLUMN system_notify_message.read_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.read_time IS '阅读时间';


--
-- Name: COLUMN system_notify_message.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.creator IS '创建者';


--
-- Name: COLUMN system_notify_message.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.create_time IS '创建时间';


--
-- Name: COLUMN system_notify_message.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.updater IS '更新者';


--
-- Name: COLUMN system_notify_message.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.update_time IS '更新时间';


--
-- Name: COLUMN system_notify_message.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.deleted IS '是否删除';


--
-- Name: COLUMN system_notify_message.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_message.tenant_id IS '租户编号';


--
-- Name: system_notify_message_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_notify_message_seq
    START WITH 11
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_notify_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_notify_template (
    id bigint NOT NULL,
    name character varying(63) NOT NULL,
    code character varying(64) NOT NULL,
    nickname character varying(255) NOT NULL,
    content character varying(1024) NOT NULL,
    type smallint NOT NULL,
    params character varying(255) DEFAULT NULL::character varying,
    status smallint NOT NULL,
    remark character varying(255) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_notify_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_notify_template IS '站内信模板表';


--
-- Name: COLUMN system_notify_template.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.id IS '主键';


--
-- Name: COLUMN system_notify_template.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.name IS '模板名称';


--
-- Name: COLUMN system_notify_template.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.code IS '模版编码';


--
-- Name: COLUMN system_notify_template.nickname; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.nickname IS '发送人名称';


--
-- Name: COLUMN system_notify_template.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.content IS '模版内容';


--
-- Name: COLUMN system_notify_template.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.type IS '类型';


--
-- Name: COLUMN system_notify_template.params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.params IS '参数数组';


--
-- Name: COLUMN system_notify_template.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.status IS '状态';


--
-- Name: COLUMN system_notify_template.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.remark IS '备注';


--
-- Name: COLUMN system_notify_template.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.creator IS '创建者';


--
-- Name: COLUMN system_notify_template.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.create_time IS '创建时间';


--
-- Name: COLUMN system_notify_template.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.updater IS '更新者';


--
-- Name: COLUMN system_notify_template.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.update_time IS '更新时间';


--
-- Name: COLUMN system_notify_template.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_notify_template.deleted IS '是否删除';


--
-- Name: system_notify_template_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_notify_template_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_oauth2_access_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_oauth2_access_token (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    user_type smallint NOT NULL,
    user_info character varying(512) NOT NULL,
    access_token character varying(255) NOT NULL,
    refresh_token character varying(32) NOT NULL,
    client_id character varying(255) NOT NULL,
    scopes character varying(255) DEFAULT NULL::character varying,
    expires_time timestamp(6) without time zone NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_oauth2_access_token; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_oauth2_access_token IS 'OAuth2 访问令牌';


--
-- Name: COLUMN system_oauth2_access_token.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.id IS '编号';


--
-- Name: COLUMN system_oauth2_access_token.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.user_id IS '用户编号';


--
-- Name: COLUMN system_oauth2_access_token.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.user_type IS '用户类型';


--
-- Name: COLUMN system_oauth2_access_token.user_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.user_info IS '用户信息';


--
-- Name: COLUMN system_oauth2_access_token.access_token; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.access_token IS '访问令牌';


--
-- Name: COLUMN system_oauth2_access_token.refresh_token; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.refresh_token IS '刷新令牌';


--
-- Name: COLUMN system_oauth2_access_token.client_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.client_id IS '客户端编号';


--
-- Name: COLUMN system_oauth2_access_token.scopes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.scopes IS '授权范围';


--
-- Name: COLUMN system_oauth2_access_token.expires_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.expires_time IS '过期时间';


--
-- Name: COLUMN system_oauth2_access_token.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.creator IS '创建者';


--
-- Name: COLUMN system_oauth2_access_token.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.create_time IS '创建时间';


--
-- Name: COLUMN system_oauth2_access_token.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.updater IS '更新者';


--
-- Name: COLUMN system_oauth2_access_token.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.update_time IS '更新时间';


--
-- Name: COLUMN system_oauth2_access_token.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.deleted IS '是否删除';


--
-- Name: COLUMN system_oauth2_access_token.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_access_token.tenant_id IS '租户编号';


--
-- Name: system_oauth2_access_token_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_oauth2_access_token_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_oauth2_approve; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_oauth2_approve (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    user_type smallint NOT NULL,
    client_id character varying(255) NOT NULL,
    scope character varying(255) DEFAULT ''::character varying NOT NULL,
    approved boolean DEFAULT false NOT NULL,
    expires_time timestamp(6) without time zone NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_oauth2_approve; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_oauth2_approve IS 'OAuth2 批准表';


--
-- Name: COLUMN system_oauth2_approve.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.id IS '编号';


--
-- Name: COLUMN system_oauth2_approve.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.user_id IS '用户编号';


--
-- Name: COLUMN system_oauth2_approve.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.user_type IS '用户类型';


--
-- Name: COLUMN system_oauth2_approve.client_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.client_id IS '客户端编号';


--
-- Name: COLUMN system_oauth2_approve.scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.scope IS '授权范围';


--
-- Name: COLUMN system_oauth2_approve.approved; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.approved IS '是否接受';


--
-- Name: COLUMN system_oauth2_approve.expires_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.expires_time IS '过期时间';


--
-- Name: COLUMN system_oauth2_approve.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.creator IS '创建者';


--
-- Name: COLUMN system_oauth2_approve.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.create_time IS '创建时间';


--
-- Name: COLUMN system_oauth2_approve.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.updater IS '更新者';


--
-- Name: COLUMN system_oauth2_approve.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.update_time IS '更新时间';


--
-- Name: COLUMN system_oauth2_approve.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.deleted IS '是否删除';


--
-- Name: COLUMN system_oauth2_approve.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_approve.tenant_id IS '租户编号';


--
-- Name: system_oauth2_approve_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_oauth2_approve_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_oauth2_client; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_oauth2_client (
    id bigint NOT NULL,
    client_id character varying(255) NOT NULL,
    secret character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    logo character varying(255) NOT NULL,
    description character varying(255) DEFAULT NULL::character varying,
    status smallint NOT NULL,
    access_token_validity_seconds integer NOT NULL,
    refresh_token_validity_seconds integer NOT NULL,
    redirect_uris character varying(255) NOT NULL,
    authorized_grant_types character varying(255) NOT NULL,
    scopes character varying(255) DEFAULT NULL::character varying,
    auto_approve_scopes character varying(255) DEFAULT NULL::character varying,
    authorities character varying(255) DEFAULT NULL::character varying,
    resource_ids character varying(255) DEFAULT NULL::character varying,
    additional_information character varying(4096) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_oauth2_client; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_oauth2_client IS 'OAuth2 客户端表';


--
-- Name: COLUMN system_oauth2_client.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.id IS '编号';


--
-- Name: COLUMN system_oauth2_client.client_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.client_id IS '客户端编号';


--
-- Name: COLUMN system_oauth2_client.secret; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.secret IS '客户端密钥';


--
-- Name: COLUMN system_oauth2_client.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.name IS '应用名';


--
-- Name: COLUMN system_oauth2_client.logo; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.logo IS '应用图标';


--
-- Name: COLUMN system_oauth2_client.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.description IS '应用描述';


--
-- Name: COLUMN system_oauth2_client.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.status IS '状态';


--
-- Name: COLUMN system_oauth2_client.access_token_validity_seconds; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.access_token_validity_seconds IS '访问令牌的有效期';


--
-- Name: COLUMN system_oauth2_client.refresh_token_validity_seconds; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.refresh_token_validity_seconds IS '刷新令牌的有效期';


--
-- Name: COLUMN system_oauth2_client.redirect_uris; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.redirect_uris IS '可重定向的 URI 地址';


--
-- Name: COLUMN system_oauth2_client.authorized_grant_types; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.authorized_grant_types IS '授权类型';


--
-- Name: COLUMN system_oauth2_client.scopes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.scopes IS '授权范围';


--
-- Name: COLUMN system_oauth2_client.auto_approve_scopes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.auto_approve_scopes IS '自动通过的授权范围';


--
-- Name: COLUMN system_oauth2_client.authorities; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.authorities IS '权限';


--
-- Name: COLUMN system_oauth2_client.resource_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.resource_ids IS '资源';


--
-- Name: COLUMN system_oauth2_client.additional_information; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.additional_information IS '附加信息';


--
-- Name: COLUMN system_oauth2_client.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.creator IS '创建者';


--
-- Name: COLUMN system_oauth2_client.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.create_time IS '创建时间';


--
-- Name: COLUMN system_oauth2_client.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.updater IS '更新者';


--
-- Name: COLUMN system_oauth2_client.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.update_time IS '更新时间';


--
-- Name: COLUMN system_oauth2_client.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_client.deleted IS '是否删除';


--
-- Name: system_oauth2_client_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_oauth2_client_seq
    START WITH 43
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_oauth2_code; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_oauth2_code (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    user_type smallint NOT NULL,
    code character varying(32) NOT NULL,
    client_id character varying(255) NOT NULL,
    scopes character varying(255) DEFAULT ''::character varying,
    expires_time timestamp(6) without time zone NOT NULL,
    redirect_uri character varying(255) DEFAULT NULL::character varying,
    state character varying(255) DEFAULT ''::character varying NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_oauth2_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_oauth2_code IS 'OAuth2 授权码表';


--
-- Name: COLUMN system_oauth2_code.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.id IS '编号';


--
-- Name: COLUMN system_oauth2_code.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.user_id IS '用户编号';


--
-- Name: COLUMN system_oauth2_code.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.user_type IS '用户类型';


--
-- Name: COLUMN system_oauth2_code.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.code IS '授权码';


--
-- Name: COLUMN system_oauth2_code.client_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.client_id IS '客户端编号';


--
-- Name: COLUMN system_oauth2_code.scopes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.scopes IS '授权范围';


--
-- Name: COLUMN system_oauth2_code.expires_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.expires_time IS '过期时间';


--
-- Name: COLUMN system_oauth2_code.redirect_uri; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.redirect_uri IS '可重定向的 URI 地址';


--
-- Name: COLUMN system_oauth2_code.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.state IS '状态';


--
-- Name: COLUMN system_oauth2_code.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.creator IS '创建者';


--
-- Name: COLUMN system_oauth2_code.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.create_time IS '创建时间';


--
-- Name: COLUMN system_oauth2_code.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.updater IS '更新者';


--
-- Name: COLUMN system_oauth2_code.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.update_time IS '更新时间';


--
-- Name: COLUMN system_oauth2_code.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.deleted IS '是否删除';


--
-- Name: COLUMN system_oauth2_code.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_code.tenant_id IS '租户编号';


--
-- Name: system_oauth2_code_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_oauth2_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_oauth2_refresh_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_oauth2_refresh_token (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    refresh_token character varying(32) NOT NULL,
    user_type smallint NOT NULL,
    client_id character varying(255) NOT NULL,
    scopes character varying(255) DEFAULT NULL::character varying,
    expires_time timestamp(6) without time zone NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_oauth2_refresh_token; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_oauth2_refresh_token IS 'OAuth2 刷新令牌';


--
-- Name: COLUMN system_oauth2_refresh_token.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.id IS '编号';


--
-- Name: COLUMN system_oauth2_refresh_token.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.user_id IS '用户编号';


--
-- Name: COLUMN system_oauth2_refresh_token.refresh_token; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.refresh_token IS '刷新令牌';


--
-- Name: COLUMN system_oauth2_refresh_token.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.user_type IS '用户类型';


--
-- Name: COLUMN system_oauth2_refresh_token.client_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.client_id IS '客户端编号';


--
-- Name: COLUMN system_oauth2_refresh_token.scopes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.scopes IS '授权范围';


--
-- Name: COLUMN system_oauth2_refresh_token.expires_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.expires_time IS '过期时间';


--
-- Name: COLUMN system_oauth2_refresh_token.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.creator IS '创建者';


--
-- Name: COLUMN system_oauth2_refresh_token.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.create_time IS '创建时间';


--
-- Name: COLUMN system_oauth2_refresh_token.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.updater IS '更新者';


--
-- Name: COLUMN system_oauth2_refresh_token.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.update_time IS '更新时间';


--
-- Name: COLUMN system_oauth2_refresh_token.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.deleted IS '是否删除';


--
-- Name: COLUMN system_oauth2_refresh_token.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_oauth2_refresh_token.tenant_id IS '租户编号';


--
-- Name: system_oauth2_refresh_token_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_oauth2_refresh_token_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_operate_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_operate_log (
    id bigint NOT NULL,
    trace_id character varying(64) DEFAULT ''::character varying NOT NULL,
    user_id bigint NOT NULL,
    user_type smallint DEFAULT 0 NOT NULL,
    type character varying(50) NOT NULL,
    sub_type character varying(50) NOT NULL,
    biz_id bigint NOT NULL,
    action character varying(2000) DEFAULT ''::character varying NOT NULL,
    success boolean DEFAULT true NOT NULL,
    extra character varying(2000) DEFAULT ''::character varying NOT NULL,
    request_method character varying(16) DEFAULT ''::character varying,
    request_url character varying(255) DEFAULT ''::character varying,
    user_ip character varying(50) DEFAULT NULL::character varying,
    user_agent character varying(512) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_operate_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_operate_log IS '操作日志记录 V2 版本';


--
-- Name: COLUMN system_operate_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.id IS '日志主键';


--
-- Name: COLUMN system_operate_log.trace_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.trace_id IS '链路追踪编号';


--
-- Name: COLUMN system_operate_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.user_id IS '用户编号';


--
-- Name: COLUMN system_operate_log.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.user_type IS '用户类型';


--
-- Name: COLUMN system_operate_log.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.type IS '操作模块类型';


--
-- Name: COLUMN system_operate_log.sub_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.sub_type IS '操作名';


--
-- Name: COLUMN system_operate_log.biz_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.biz_id IS '操作数据模块编号';


--
-- Name: COLUMN system_operate_log.action; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.action IS '操作内容';


--
-- Name: COLUMN system_operate_log.success; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.success IS '操作结果';


--
-- Name: COLUMN system_operate_log.extra; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.extra IS '拓展字段';


--
-- Name: COLUMN system_operate_log.request_method; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.request_method IS '请求方法名';


--
-- Name: COLUMN system_operate_log.request_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.request_url IS '请求地址';


--
-- Name: COLUMN system_operate_log.user_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.user_ip IS '用户 IP';


--
-- Name: COLUMN system_operate_log.user_agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.user_agent IS '浏览器 UA';


--
-- Name: COLUMN system_operate_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.creator IS '创建者';


--
-- Name: COLUMN system_operate_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.create_time IS '创建时间';


--
-- Name: COLUMN system_operate_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.updater IS '更新者';


--
-- Name: COLUMN system_operate_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.update_time IS '更新时间';


--
-- Name: COLUMN system_operate_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.deleted IS '是否删除';


--
-- Name: COLUMN system_operate_log.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_operate_log.tenant_id IS '租户编号';


--
-- Name: system_operate_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_operate_log_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_organization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_organization (
    id bigint NOT NULL,
    tenant_id bigint,
    org_code character varying(50) NOT NULL,
    org_name character varying(100) NOT NULL,
    org_type smallint,
    parent_id bigint,
    parent_ids character varying(500),
    level integer,
    leader_id bigint,
    phone character varying(20),
    email character varying(100),
    address character varying(255),
    sort_order integer,
    status smallint,
    create_time timestamp(6) without time zone,
    creator character varying(32),
    update_time timestamp(6) without time zone,
    updater character varying(32),
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_organization; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_organization IS '组织表';


--
-- Name: COLUMN system_organization.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.id IS '主键ID';


--
-- Name: COLUMN system_organization.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.tenant_id IS '租户ID';


--
-- Name: COLUMN system_organization.org_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.org_code IS '组织编码';


--
-- Name: COLUMN system_organization.org_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.org_name IS '组织名称';


--
-- Name: COLUMN system_organization.org_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.org_type IS '组织类型: 1-公司 2-分公司 3-部门';


--
-- Name: COLUMN system_organization.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.parent_id IS '父组织ID';


--
-- Name: COLUMN system_organization.parent_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.parent_ids IS '父组织ID路径(逗号分隔)';


--
-- Name: COLUMN system_organization.level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.level IS '层级';


--
-- Name: COLUMN system_organization.leader_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.leader_id IS '负责人ID';


--
-- Name: COLUMN system_organization.phone; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.phone IS '联系电话';


--
-- Name: COLUMN system_organization.email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.email IS '邮箱';


--
-- Name: COLUMN system_organization.address; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.address IS '地址';


--
-- Name: COLUMN system_organization.sort_order; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.sort_order IS '排序';


--
-- Name: COLUMN system_organization.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.status IS '状态: 0-禁用 1-启用';


--
-- Name: COLUMN system_organization.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.creator IS '创建人ID';


--
-- Name: COLUMN system_organization.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.updater IS '更新人ID';


--
-- Name: COLUMN system_organization.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_organization.deleted IS '逻辑删除: 0-未删除 1-已删除';


--
-- Name: system_post; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_post (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(50) NOT NULL,
    sort integer NOT NULL,
    status smallint NOT NULL,
    remark character varying(500) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_post; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_post IS '岗位信息表';


--
-- Name: COLUMN system_post.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.id IS '岗位ID';


--
-- Name: COLUMN system_post.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.code IS '岗位编码';


--
-- Name: COLUMN system_post.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.name IS '岗位名称';


--
-- Name: COLUMN system_post.sort; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.sort IS '显示顺序';


--
-- Name: COLUMN system_post.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.status IS '状态（0正常 1停用）';


--
-- Name: COLUMN system_post.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.remark IS '备注';


--
-- Name: COLUMN system_post.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.creator IS '创建者';


--
-- Name: COLUMN system_post.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.create_time IS '创建时间';


--
-- Name: COLUMN system_post.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.updater IS '更新者';


--
-- Name: COLUMN system_post.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.update_time IS '更新时间';


--
-- Name: COLUMN system_post.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.deleted IS '是否删除';


--
-- Name: COLUMN system_post.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_post.tenant_id IS '租户编号';


--
-- Name: system_post_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_post_seq
    START WITH 8
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_role (
    id bigint NOT NULL,
    name character varying(30) NOT NULL,
    code character varying(100) NOT NULL,
    sort integer NOT NULL,
    data_scope smallint DEFAULT 1 NOT NULL,
    data_scope_dept_ids character varying(500) DEFAULT ''::character varying NOT NULL,
    status smallint NOT NULL,
    type smallint NOT NULL,
    remark character varying(500) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL,
    global_project_view smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_role IS '角色信息表';


--
-- Name: COLUMN system_role.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.id IS '角色ID';


--
-- Name: COLUMN system_role.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.name IS '角色名称';


--
-- Name: COLUMN system_role.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.code IS '角色权限字符串';


--
-- Name: COLUMN system_role.sort; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.sort IS '显示顺序';


--
-- Name: COLUMN system_role.data_scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.data_scope IS '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）';


--
-- Name: COLUMN system_role.data_scope_dept_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.data_scope_dept_ids IS '数据范围(指定部门数组)';


--
-- Name: COLUMN system_role.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.status IS '角色状态（0正常 1停用）';


--
-- Name: COLUMN system_role.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.type IS '角色类型';


--
-- Name: COLUMN system_role.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.remark IS '备注';


--
-- Name: COLUMN system_role.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.creator IS '创建者';


--
-- Name: COLUMN system_role.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.create_time IS '创建时间';


--
-- Name: COLUMN system_role.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.updater IS '更新者';


--
-- Name: COLUMN system_role.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.update_time IS '更新时间';


--
-- Name: COLUMN system_role.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.deleted IS '是否删除';


--
-- Name: COLUMN system_role.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.tenant_id IS '租户编号';


--
-- Name: COLUMN system_role.global_project_view; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role.global_project_view IS '全局项目访问权（0=关闭 1=允许访问所有项目空间，只读）';


--
-- Name: system_role_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_role_menu (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    menu_id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_role_menu; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_role_menu IS '角色和菜单关联表';


--
-- Name: COLUMN system_role_menu.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.id IS '自增编号';


--
-- Name: COLUMN system_role_menu.role_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.role_id IS '角色ID';


--
-- Name: COLUMN system_role_menu.menu_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.menu_id IS '菜单ID';


--
-- Name: COLUMN system_role_menu.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.creator IS '创建者';


--
-- Name: COLUMN system_role_menu.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.create_time IS '创建时间';


--
-- Name: COLUMN system_role_menu.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.updater IS '更新者';


--
-- Name: COLUMN system_role_menu.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.update_time IS '更新时间';


--
-- Name: COLUMN system_role_menu.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.deleted IS '是否删除';


--
-- Name: COLUMN system_role_menu.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_role_menu.tenant_id IS '租户编号';


--
-- Name: system_role_menu_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_role_menu_seq
    START WITH 6365
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_role_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_role_seq
    START WITH 156
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_sms_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_sms_channel (
    id bigint NOT NULL,
    signature character varying(12) NOT NULL,
    code character varying(63) NOT NULL,
    status smallint NOT NULL,
    remark character varying(255) DEFAULT NULL::character varying,
    api_key character varying(128) NOT NULL,
    api_secret character varying(128) DEFAULT NULL::character varying,
    callback_url character varying(255) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_sms_channel; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_sms_channel IS '短信渠道';


--
-- Name: COLUMN system_sms_channel.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.id IS '编号';


--
-- Name: COLUMN system_sms_channel.signature; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.signature IS '短信签名';


--
-- Name: COLUMN system_sms_channel.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.code IS '渠道编码';


--
-- Name: COLUMN system_sms_channel.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.status IS '开启状态';


--
-- Name: COLUMN system_sms_channel.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.remark IS '备注';


--
-- Name: COLUMN system_sms_channel.api_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.api_key IS '短信 API 的账号';


--
-- Name: COLUMN system_sms_channel.api_secret; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.api_secret IS '短信 API 的秘钥';


--
-- Name: COLUMN system_sms_channel.callback_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.callback_url IS '短信发送回调 URL';


--
-- Name: COLUMN system_sms_channel.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.creator IS '创建者';


--
-- Name: COLUMN system_sms_channel.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.create_time IS '创建时间';


--
-- Name: COLUMN system_sms_channel.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.updater IS '更新者';


--
-- Name: COLUMN system_sms_channel.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.update_time IS '更新时间';


--
-- Name: COLUMN system_sms_channel.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_channel.deleted IS '是否删除';


--
-- Name: system_sms_channel_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_sms_channel_seq
    START WITH 8
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_sms_code; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_sms_code (
    id bigint NOT NULL,
    mobile character varying(11) NOT NULL,
    code character varying(6) NOT NULL,
    create_ip character varying(15) NOT NULL,
    scene smallint NOT NULL,
    today_index smallint NOT NULL,
    used smallint NOT NULL,
    used_time timestamp(6) without time zone,
    used_ip character varying(255) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_sms_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_sms_code IS '手机验证码';


--
-- Name: COLUMN system_sms_code.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.id IS '编号';


--
-- Name: COLUMN system_sms_code.mobile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.mobile IS '手机号';


--
-- Name: COLUMN system_sms_code.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.code IS '验证码';


--
-- Name: COLUMN system_sms_code.create_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.create_ip IS '创建 IP';


--
-- Name: COLUMN system_sms_code.scene; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.scene IS '发送场景';


--
-- Name: COLUMN system_sms_code.today_index; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.today_index IS '今日发送的第几条';


--
-- Name: COLUMN system_sms_code.used; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.used IS '是否使用';


--
-- Name: COLUMN system_sms_code.used_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.used_time IS '使用时间';


--
-- Name: COLUMN system_sms_code.used_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.used_ip IS '使用 IP';


--
-- Name: COLUMN system_sms_code.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.creator IS '创建者';


--
-- Name: COLUMN system_sms_code.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.create_time IS '创建时间';


--
-- Name: COLUMN system_sms_code.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.updater IS '更新者';


--
-- Name: COLUMN system_sms_code.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.update_time IS '更新时间';


--
-- Name: COLUMN system_sms_code.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.deleted IS '是否删除';


--
-- Name: COLUMN system_sms_code.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_code.tenant_id IS '租户编号';


--
-- Name: system_sms_code_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_sms_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_sms_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_sms_log (
    id bigint NOT NULL,
    channel_id bigint NOT NULL,
    channel_code character varying(63) NOT NULL,
    template_id bigint NOT NULL,
    template_code character varying(63) NOT NULL,
    template_type smallint NOT NULL,
    template_content character varying(255) NOT NULL,
    template_params character varying(255) NOT NULL,
    api_template_id character varying(63) NOT NULL,
    mobile character varying(11) NOT NULL,
    user_id bigint,
    user_type smallint,
    send_status smallint DEFAULT 0 NOT NULL,
    send_time timestamp(6) without time zone,
    api_send_code character varying(63) DEFAULT NULL::character varying,
    api_send_msg character varying(255) DEFAULT NULL::character varying,
    api_request_id character varying(255) DEFAULT NULL::character varying,
    api_serial_no character varying(255) DEFAULT NULL::character varying,
    receive_status smallint DEFAULT 0 NOT NULL,
    receive_time timestamp(6) without time zone,
    api_receive_code character varying(63) DEFAULT NULL::character varying,
    api_receive_msg character varying(255) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_sms_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_sms_log IS '短信日志';


--
-- Name: COLUMN system_sms_log.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.id IS '编号';


--
-- Name: COLUMN system_sms_log.channel_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.channel_id IS '短信渠道编号';


--
-- Name: COLUMN system_sms_log.channel_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.channel_code IS '短信渠道编码';


--
-- Name: COLUMN system_sms_log.template_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.template_id IS '模板编号';


--
-- Name: COLUMN system_sms_log.template_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.template_code IS '模板编码';


--
-- Name: COLUMN system_sms_log.template_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.template_type IS '短信类型';


--
-- Name: COLUMN system_sms_log.template_content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.template_content IS '短信内容';


--
-- Name: COLUMN system_sms_log.template_params; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.template_params IS '短信参数';


--
-- Name: COLUMN system_sms_log.api_template_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.api_template_id IS '短信 API 的模板编号';


--
-- Name: COLUMN system_sms_log.mobile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.mobile IS '手机号';


--
-- Name: COLUMN system_sms_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.user_id IS '用户编号';


--
-- Name: COLUMN system_sms_log.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.user_type IS '用户类型';


--
-- Name: COLUMN system_sms_log.send_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.send_status IS '发送状态';


--
-- Name: COLUMN system_sms_log.send_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.send_time IS '发送时间';


--
-- Name: COLUMN system_sms_log.api_send_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.api_send_code IS '短信 API 发送结果的编码';


--
-- Name: COLUMN system_sms_log.api_send_msg; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.api_send_msg IS '短信 API 发送失败的提示';


--
-- Name: COLUMN system_sms_log.api_request_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.api_request_id IS '短信 API 发送返回的唯一请求 ID';


--
-- Name: COLUMN system_sms_log.api_serial_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.api_serial_no IS '短信 API 发送返回的序号';


--
-- Name: COLUMN system_sms_log.receive_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.receive_status IS '接收状态';


--
-- Name: COLUMN system_sms_log.receive_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.receive_time IS '接收时间';


--
-- Name: COLUMN system_sms_log.api_receive_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.api_receive_code IS 'API 接收结果的编码';


--
-- Name: COLUMN system_sms_log.api_receive_msg; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.api_receive_msg IS 'API 接收结果的说明';


--
-- Name: COLUMN system_sms_log.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.creator IS '创建者';


--
-- Name: COLUMN system_sms_log.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.create_time IS '创建时间';


--
-- Name: COLUMN system_sms_log.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.updater IS '更新者';


--
-- Name: COLUMN system_sms_log.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.update_time IS '更新时间';


--
-- Name: COLUMN system_sms_log.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_sms_log.deleted IS '是否删除';


--
-- Name: system_sms_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_sms_log_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_sms_template_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_sms_template_seq
    START WITH 20
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_social_client; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_social_client (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    social_type smallint NOT NULL,
    user_type smallint NOT NULL,
    client_id character varying(255) NOT NULL,
    client_secret character varying(255) NOT NULL,
    agent_id character varying(255) DEFAULT NULL::character varying,
    public_key character varying(2048) DEFAULT NULL::character varying,
    status smallint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_social_client; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_social_client IS '社交客户端表';


--
-- Name: COLUMN system_social_client.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.id IS '编号';


--
-- Name: COLUMN system_social_client.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.name IS '应用名';


--
-- Name: COLUMN system_social_client.social_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.social_type IS '社交平台的类型';


--
-- Name: COLUMN system_social_client.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.user_type IS '用户类型';


--
-- Name: COLUMN system_social_client.client_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.client_id IS '客户端编号';


--
-- Name: COLUMN system_social_client.client_secret; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.client_secret IS '客户端密钥';


--
-- Name: COLUMN system_social_client.agent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.agent_id IS '代理编号';


--
-- Name: COLUMN system_social_client.public_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.public_key IS 'publicKey 公钥';


--
-- Name: COLUMN system_social_client.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.status IS '状态';


--
-- Name: COLUMN system_social_client.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.creator IS '创建者';


--
-- Name: COLUMN system_social_client.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.create_time IS '创建时间';


--
-- Name: COLUMN system_social_client.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.updater IS '更新者';


--
-- Name: COLUMN system_social_client.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.update_time IS '更新时间';


--
-- Name: COLUMN system_social_client.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.deleted IS '是否删除';


--
-- Name: COLUMN system_social_client.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_client.tenant_id IS '租户编号';


--
-- Name: system_social_client_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_social_client_seq
    START WITH 48
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_social_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_social_user (
    id bigint NOT NULL,
    type smallint NOT NULL,
    openid character varying(32) NOT NULL,
    token character varying(256) DEFAULT NULL::character varying,
    raw_token_info character varying(1024) NOT NULL,
    nickname character varying(32) NOT NULL,
    avatar character varying(255) DEFAULT NULL::character varying,
    raw_user_info character varying(1024) NOT NULL,
    code character varying(256) NOT NULL,
    state character varying(256) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_social_user; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_social_user IS '社交用户表';


--
-- Name: COLUMN system_social_user.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.id IS '主键(自增策略)';


--
-- Name: COLUMN system_social_user.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.type IS '社交平台的类型';


--
-- Name: COLUMN system_social_user.openid; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.openid IS '社交 openid';


--
-- Name: COLUMN system_social_user.token; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.token IS '社交 token';


--
-- Name: COLUMN system_social_user.raw_token_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.raw_token_info IS '原始 Token 数据，一般是 JSON 格式';


--
-- Name: COLUMN system_social_user.nickname; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.nickname IS '用户昵称';


--
-- Name: COLUMN system_social_user.avatar; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.avatar IS '用户头像';


--
-- Name: COLUMN system_social_user.raw_user_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.raw_user_info IS '原始用户数据，一般是 JSON 格式';


--
-- Name: COLUMN system_social_user.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.code IS '最后一次的认证 code';


--
-- Name: COLUMN system_social_user.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.state IS '最后一次的认证 state';


--
-- Name: COLUMN system_social_user.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.creator IS '创建者';


--
-- Name: COLUMN system_social_user.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.create_time IS '创建时间';


--
-- Name: COLUMN system_social_user.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.updater IS '更新者';


--
-- Name: COLUMN system_social_user.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.update_time IS '更新时间';


--
-- Name: COLUMN system_social_user.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.deleted IS '是否删除';


--
-- Name: COLUMN system_social_user.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user.tenant_id IS '租户编号';


--
-- Name: system_social_user_bind; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_social_user_bind (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    user_type smallint NOT NULL,
    social_type smallint NOT NULL,
    social_user_id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_social_user_bind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_social_user_bind IS '社交绑定表';


--
-- Name: COLUMN system_social_user_bind.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.id IS '主键(自增策略)';


--
-- Name: COLUMN system_social_user_bind.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.user_id IS '用户编号';


--
-- Name: COLUMN system_social_user_bind.user_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.user_type IS '用户类型';


--
-- Name: COLUMN system_social_user_bind.social_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.social_type IS '社交平台的类型';


--
-- Name: COLUMN system_social_user_bind.social_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.social_user_id IS '社交用户的编号';


--
-- Name: COLUMN system_social_user_bind.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.creator IS '创建者';


--
-- Name: COLUMN system_social_user_bind.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.create_time IS '创建时间';


--
-- Name: COLUMN system_social_user_bind.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.updater IS '更新者';


--
-- Name: COLUMN system_social_user_bind.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.update_time IS '更新时间';


--
-- Name: COLUMN system_social_user_bind.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.deleted IS '是否删除';


--
-- Name: COLUMN system_social_user_bind.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_social_user_bind.tenant_id IS '租户编号';


--
-- Name: system_social_user_bind_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_social_user_bind_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_social_user_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_social_user_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_tenant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_tenant (
    id bigint NOT NULL,
    name character varying(30) NOT NULL,
    contact_user_id bigint,
    contact_name character varying(30) NOT NULL,
    contact_mobile character varying(500) DEFAULT NULL::character varying,
    status smallint DEFAULT 0 NOT NULL,
    websites character varying(1024) DEFAULT ''::character varying,
    package_id bigint NOT NULL,
    expire_time timestamp(6) without time zone NOT NULL,
    account_count integer NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_tenant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_tenant IS '租户表';


--
-- Name: COLUMN system_tenant.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.id IS '租户编号';


--
-- Name: COLUMN system_tenant.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.name IS '租户名';


--
-- Name: COLUMN system_tenant.contact_user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.contact_user_id IS '联系人的用户编号';


--
-- Name: COLUMN system_tenant.contact_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.contact_name IS '联系人';


--
-- Name: COLUMN system_tenant.contact_mobile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.contact_mobile IS '联系手机';


--
-- Name: COLUMN system_tenant.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.status IS '租户状态';


--
-- Name: COLUMN system_tenant.websites; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.websites IS '绑定域名数组';


--
-- Name: COLUMN system_tenant.package_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.package_id IS '租户套餐编号';


--
-- Name: COLUMN system_tenant.expire_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.expire_time IS '过期时间';


--
-- Name: COLUMN system_tenant.account_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.account_count IS '账号数量';


--
-- Name: COLUMN system_tenant.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.creator IS '创建者';


--
-- Name: COLUMN system_tenant.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.create_time IS '创建时间';


--
-- Name: COLUMN system_tenant.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.updater IS '更新者';


--
-- Name: COLUMN system_tenant.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.update_time IS '更新时间';


--
-- Name: COLUMN system_tenant.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant.deleted IS '是否删除';


--
-- Name: system_tenant_package; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_tenant_package (
    id bigint NOT NULL,
    name character varying(30) NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    remark character varying(256) DEFAULT ''::character varying,
    menu_ids character varying(4096) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_tenant_package; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_tenant_package IS '租户套餐表';


--
-- Name: COLUMN system_tenant_package.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.id IS '套餐编号';


--
-- Name: COLUMN system_tenant_package.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.name IS '套餐名';


--
-- Name: COLUMN system_tenant_package.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.status IS '租户状态（0正常 1停用）';


--
-- Name: COLUMN system_tenant_package.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.remark IS '备注';


--
-- Name: COLUMN system_tenant_package.menu_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.menu_ids IS '关联的菜单编号';


--
-- Name: COLUMN system_tenant_package.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.creator IS '创建者';


--
-- Name: COLUMN system_tenant_package.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.create_time IS '创建时间';


--
-- Name: COLUMN system_tenant_package.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.updater IS '更新者';


--
-- Name: COLUMN system_tenant_package.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.update_time IS '更新时间';


--
-- Name: COLUMN system_tenant_package.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_tenant_package.deleted IS '是否删除';


--
-- Name: system_tenant_package_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_tenant_package_seq
    START WITH 112
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_tenant_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_tenant_seq
    START WITH 123
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_user_dept; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_user_dept (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    dept_id bigint NOT NULL,
    post character varying(100),
    is_main smallint DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying NOT NULL,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying NOT NULL,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_user_dept; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_user_dept IS '用户与部门关联表';


--
-- Name: COLUMN system_user_dept.is_main; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_dept.is_main IS '是否主部门：0=否，1=是';


--
-- Name: system_user_post; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_user_post (
    id bigint NOT NULL,
    user_id bigint DEFAULT 0 NOT NULL,
    post_id bigint DEFAULT 0 NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_user_post; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_user_post IS '用户岗位表';


--
-- Name: COLUMN system_user_post.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.id IS 'id';


--
-- Name: COLUMN system_user_post.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.user_id IS '用户ID';


--
-- Name: COLUMN system_user_post.post_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.post_id IS '岗位ID';


--
-- Name: COLUMN system_user_post.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.creator IS '创建者';


--
-- Name: COLUMN system_user_post.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.create_time IS '创建时间';


--
-- Name: COLUMN system_user_post.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.updater IS '更新者';


--
-- Name: COLUMN system_user_post.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.update_time IS '更新时间';


--
-- Name: COLUMN system_user_post.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.deleted IS '是否删除';


--
-- Name: COLUMN system_user_post.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_post.tenant_id IS '租户编号';


--
-- Name: system_user_post_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_user_post_seq
    START WITH 130
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_user_role (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE system_user_role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_user_role IS '用户和角色关联表';


--
-- Name: COLUMN system_user_role.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.id IS '自增编号';


--
-- Name: COLUMN system_user_role.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.user_id IS '用户ID';


--
-- Name: COLUMN system_user_role.role_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.role_id IS '角色ID';


--
-- Name: COLUMN system_user_role.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.creator IS '创建者';


--
-- Name: COLUMN system_user_role.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.create_time IS '创建时间';


--
-- Name: COLUMN system_user_role.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.updater IS '更新者';


--
-- Name: COLUMN system_user_role.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.update_time IS '更新时间';


--
-- Name: COLUMN system_user_role.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.deleted IS '是否删除';


--
-- Name: COLUMN system_user_role.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_user_role.tenant_id IS '租户编号';


--
-- Name: system_user_role_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_user_role_seq
    START WITH 55
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_users (
    id bigint NOT NULL,
    username character varying(30) NOT NULL,
    password character varying(100) DEFAULT ''::character varying NOT NULL,
    nickname character varying(30) NOT NULL,
    remark character varying(500) DEFAULT NULL::character varying,
    dept_id bigint,
    post_ids character varying(255) DEFAULT NULL::character varying,
    email character varying(50) DEFAULT ''::character varying,
    mobile character varying(11) DEFAULT ''::character varying,
    sex smallint DEFAULT 0,
    avatar character varying(512) DEFAULT ''::character varying,
    status smallint DEFAULT 0 NOT NULL,
    login_ip character varying(50) DEFAULT ''::character varying,
    login_date timestamp(6) without time zone,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL,
    org_id bigint,
    password_update_time timestamp(6) without time zone,
    nickname_pinyin character varying(255),
    nickname_pinyin_initial character varying(64)
);


--
-- Name: TABLE system_users; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_users IS '用户信息表';


--
-- Name: COLUMN system_users.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.id IS '用户ID';


--
-- Name: COLUMN system_users.username; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.username IS '用户账号';


--
-- Name: COLUMN system_users.password; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.password IS '密码';


--
-- Name: COLUMN system_users.nickname; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.nickname IS '用户昵称';


--
-- Name: COLUMN system_users.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.remark IS '备注';


--
-- Name: COLUMN system_users.dept_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.dept_id IS '部门ID';


--
-- Name: COLUMN system_users.post_ids; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.post_ids IS '岗位编号数组';


--
-- Name: COLUMN system_users.email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.email IS '用户邮箱';


--
-- Name: COLUMN system_users.mobile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.mobile IS '手机号码';


--
-- Name: COLUMN system_users.sex; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.sex IS '用户性别';


--
-- Name: COLUMN system_users.avatar; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.avatar IS '头像地址';


--
-- Name: COLUMN system_users.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.status IS '帐号状态（0正常 1停用）';


--
-- Name: COLUMN system_users.login_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.login_ip IS '最后登录IP';


--
-- Name: COLUMN system_users.login_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.login_date IS '最后登录时间';


--
-- Name: COLUMN system_users.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.creator IS '创建者';


--
-- Name: COLUMN system_users.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.create_time IS '创建时间';


--
-- Name: COLUMN system_users.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.updater IS '更新者';


--
-- Name: COLUMN system_users.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.update_time IS '更新时间';


--
-- Name: COLUMN system_users.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.deleted IS '是否删除';


--
-- Name: COLUMN system_users.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.tenant_id IS '租户编号';


--
-- Name: COLUMN system_users.org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.org_id IS '组织ID';


--
-- Name: COLUMN system_users.password_update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.password_update_time IS '密码最后修改时间，NULL 表示从未修改（使用初始密码）';


--
-- Name: COLUMN system_users.nickname_pinyin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.nickname_pinyin IS '用户昵称全拼，由服务端根据 nickname 自动生成';


--
-- Name: COLUMN system_users.nickname_pinyin_initial; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.system_users.nickname_pinyin_initial IS '用户昵称拼音首字母，由服务端根据 nickname 自动生成';


--
-- Name: system_users_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_users_seq
    START WITH 145
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: yudao_demo01_contact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.yudao_demo01_contact (
    id bigint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    sex smallint NOT NULL,
    birthday timestamp(6) without time zone NOT NULL,
    description character varying(255) NOT NULL,
    avatar character varying(512) DEFAULT NULL::character varying,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE yudao_demo01_contact; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.yudao_demo01_contact IS '示例联系人表';


--
-- Name: COLUMN yudao_demo01_contact.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.id IS '编号';


--
-- Name: COLUMN yudao_demo01_contact.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.name IS '名字';


--
-- Name: COLUMN yudao_demo01_contact.sex; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.sex IS '性别';


--
-- Name: COLUMN yudao_demo01_contact.birthday; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.birthday IS '出生年';


--
-- Name: COLUMN yudao_demo01_contact.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.description IS '简介';


--
-- Name: COLUMN yudao_demo01_contact.avatar; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.avatar IS '头像';


--
-- Name: COLUMN yudao_demo01_contact.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.creator IS '创建者';


--
-- Name: COLUMN yudao_demo01_contact.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.create_time IS '创建时间';


--
-- Name: COLUMN yudao_demo01_contact.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.updater IS '更新者';


--
-- Name: COLUMN yudao_demo01_contact.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.update_time IS '更新时间';


--
-- Name: COLUMN yudao_demo01_contact.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.deleted IS '是否删除';


--
-- Name: COLUMN yudao_demo01_contact.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo01_contact.tenant_id IS '租户编号';


--
-- Name: yudao_demo01_contact_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.yudao_demo01_contact_seq
    START WITH 2
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: yudao_demo02_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.yudao_demo02_category (
    id bigint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    parent_id bigint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE yudao_demo02_category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.yudao_demo02_category IS '示例分类表';


--
-- Name: COLUMN yudao_demo02_category.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.id IS '编号';


--
-- Name: COLUMN yudao_demo02_category.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.name IS '名字';


--
-- Name: COLUMN yudao_demo02_category.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.parent_id IS '父级编号';


--
-- Name: COLUMN yudao_demo02_category.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.creator IS '创建者';


--
-- Name: COLUMN yudao_demo02_category.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.create_time IS '创建时间';


--
-- Name: COLUMN yudao_demo02_category.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.updater IS '更新者';


--
-- Name: COLUMN yudao_demo02_category.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.update_time IS '更新时间';


--
-- Name: COLUMN yudao_demo02_category.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.deleted IS '是否删除';


--
-- Name: COLUMN yudao_demo02_category.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo02_category.tenant_id IS '租户编号';


--
-- Name: yudao_demo02_category_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.yudao_demo02_category_seq
    START WITH 8
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: yudao_demo03_course; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.yudao_demo03_course (
    id bigint NOT NULL,
    student_id bigint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    score smallint NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE yudao_demo03_course; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.yudao_demo03_course IS '学生课程表';


--
-- Name: COLUMN yudao_demo03_course.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.id IS '编号';


--
-- Name: COLUMN yudao_demo03_course.student_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.student_id IS '学生编号';


--
-- Name: COLUMN yudao_demo03_course.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.name IS '名字';


--
-- Name: COLUMN yudao_demo03_course.score; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.score IS '分数';


--
-- Name: COLUMN yudao_demo03_course.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.creator IS '创建者';


--
-- Name: COLUMN yudao_demo03_course.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.create_time IS '创建时间';


--
-- Name: COLUMN yudao_demo03_course.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.updater IS '更新者';


--
-- Name: COLUMN yudao_demo03_course.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.update_time IS '更新时间';


--
-- Name: COLUMN yudao_demo03_course.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.deleted IS '是否删除';


--
-- Name: COLUMN yudao_demo03_course.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_course.tenant_id IS '租户编号';


--
-- Name: yudao_demo03_course_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.yudao_demo03_course_seq
    START WITH 21
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: yudao_demo03_grade; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.yudao_demo03_grade (
    id bigint NOT NULL,
    student_id bigint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    teacher character varying(255) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE yudao_demo03_grade; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.yudao_demo03_grade IS '学生班级表';


--
-- Name: COLUMN yudao_demo03_grade.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.id IS '编号';


--
-- Name: COLUMN yudao_demo03_grade.student_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.student_id IS '学生编号';


--
-- Name: COLUMN yudao_demo03_grade.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.name IS '名字';


--
-- Name: COLUMN yudao_demo03_grade.teacher; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.teacher IS '班主任';


--
-- Name: COLUMN yudao_demo03_grade.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.creator IS '创建者';


--
-- Name: COLUMN yudao_demo03_grade.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.create_time IS '创建时间';


--
-- Name: COLUMN yudao_demo03_grade.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.updater IS '更新者';


--
-- Name: COLUMN yudao_demo03_grade.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.update_time IS '更新时间';


--
-- Name: COLUMN yudao_demo03_grade.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.deleted IS '是否删除';


--
-- Name: COLUMN yudao_demo03_grade.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_grade.tenant_id IS '租户编号';


--
-- Name: yudao_demo03_grade_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.yudao_demo03_grade_seq
    START WITH 10
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: yudao_demo03_student; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.yudao_demo03_student (
    id bigint NOT NULL,
    name character varying(100) DEFAULT ''::character varying NOT NULL,
    sex smallint NOT NULL,
    birthday timestamp(6) without time zone NOT NULL,
    description character varying(255) NOT NULL,
    creator character varying(64) DEFAULT ''::character varying,
    create_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updater character varying(64) DEFAULT ''::character varying,
    update_time timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    tenant_id bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE yudao_demo03_student; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.yudao_demo03_student IS '学生表';


--
-- Name: COLUMN yudao_demo03_student.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.id IS '编号';


--
-- Name: COLUMN yudao_demo03_student.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.name IS '名字';


--
-- Name: COLUMN yudao_demo03_student.sex; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.sex IS '性别';


--
-- Name: COLUMN yudao_demo03_student.birthday; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.birthday IS '出生日期';


--
-- Name: COLUMN yudao_demo03_student.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.description IS '简介';


--
-- Name: COLUMN yudao_demo03_student.creator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.creator IS '创建者';


--
-- Name: COLUMN yudao_demo03_student.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.create_time IS '创建时间';


--
-- Name: COLUMN yudao_demo03_student.updater; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.updater IS '更新者';


--
-- Name: COLUMN yudao_demo03_student.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.update_time IS '更新时间';


--
-- Name: COLUMN yudao_demo03_student.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.deleted IS '是否删除';


--
-- Name: COLUMN yudao_demo03_student.tenant_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.yudao_demo03_student.tenant_id IS '租户编号';


--
-- Name: yudao_demo03_student_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.yudao_demo03_student_seq
    START WITH 10
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: act_evt_log log_nr_; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_evt_log ALTER COLUMN log_nr_ SET DEFAULT nextval('public.act_evt_log_log_nr__seq'::regclass);


--
-- Name: act_hi_tsk_log id_; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_tsk_log ALTER COLUMN id_ SET DEFAULT nextval('public.act_hi_tsk_log_id__seq'::regclass);


--
-- Name: nocode_detail_position id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_detail_position ALTER COLUMN id SET DEFAULT nextval('public.nocode_detail_position_id_seq'::regclass);


--
-- Name: nocode_document_receipt id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_document_receipt ALTER COLUMN id SET DEFAULT nextval('public.nocode_document_receipt_id_seq'::regclass);


--
-- Name: nocode_record_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_history ALTER COLUMN id SET DEFAULT nextval('public.nocode_record_history_id_seq'::regclass);


--
-- Name: nocode_task_entry_access id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_access ALTER COLUMN id SET DEFAULT nextval('public.nocode_task_entry_access_id_seq'::regclass);


--
-- Name: flw_channel_definition FLW_CHANNEL_DEFINITION_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_channel_definition
    ADD CONSTRAINT "FLW_CHANNEL_DEFINITION_pkey" PRIMARY KEY (id_);


--
-- Name: flw_event_definition FLW_EVENT_DEFINITION_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_event_definition
    ADD CONSTRAINT "FLW_EVENT_DEFINITION_pkey" PRIMARY KEY (id_);


--
-- Name: flw_event_deployment FLW_EVENT_DEPLOYMENT_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_event_deployment
    ADD CONSTRAINT "FLW_EVENT_DEPLOYMENT_pkey" PRIMARY KEY (id_);


--
-- Name: flw_event_resource FLW_EVENT_RESOURCE_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_event_resource
    ADD CONSTRAINT "FLW_EVENT_RESOURCE_pkey" PRIMARY KEY (id_);


--
-- Name: act_evt_log act_evt_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_evt_log
    ADD CONSTRAINT act_evt_log_pkey PRIMARY KEY (log_nr_);


--
-- Name: act_ge_bytearray act_ge_bytearray_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ge_bytearray
    ADD CONSTRAINT act_ge_bytearray_pkey PRIMARY KEY (id_);


--
-- Name: act_ge_property act_ge_property_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ge_property
    ADD CONSTRAINT act_ge_property_pkey PRIMARY KEY (name_);


--
-- Name: act_hi_actinst act_hi_actinst_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_actinst
    ADD CONSTRAINT act_hi_actinst_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_attachment act_hi_attachment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_attachment
    ADD CONSTRAINT act_hi_attachment_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_comment act_hi_comment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_comment
    ADD CONSTRAINT act_hi_comment_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_detail act_hi_detail_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_detail
    ADD CONSTRAINT act_hi_detail_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_entitylink act_hi_entitylink_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_entitylink
    ADD CONSTRAINT act_hi_entitylink_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_identitylink act_hi_identitylink_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_identitylink
    ADD CONSTRAINT act_hi_identitylink_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_procinst act_hi_procinst_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_procinst
    ADD CONSTRAINT act_hi_procinst_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_procinst act_hi_procinst_proc_inst_id__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_procinst
    ADD CONSTRAINT act_hi_procinst_proc_inst_id__key UNIQUE (proc_inst_id_);


--
-- Name: act_hi_taskinst act_hi_taskinst_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_taskinst
    ADD CONSTRAINT act_hi_taskinst_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_tsk_log act_hi_tsk_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_tsk_log
    ADD CONSTRAINT act_hi_tsk_log_pkey PRIMARY KEY (id_);


--
-- Name: act_hi_varinst act_hi_varinst_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_hi_varinst
    ADD CONSTRAINT act_hi_varinst_pkey PRIMARY KEY (id_);


--
-- Name: act_id_bytearray act_id_bytearray_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_bytearray
    ADD CONSTRAINT act_id_bytearray_pkey PRIMARY KEY (id_);


--
-- Name: act_id_group act_id_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_group
    ADD CONSTRAINT act_id_group_pkey PRIMARY KEY (id_);


--
-- Name: act_id_info act_id_info_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_info
    ADD CONSTRAINT act_id_info_pkey PRIMARY KEY (id_);


--
-- Name: act_id_membership act_id_membership_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_membership
    ADD CONSTRAINT act_id_membership_pkey PRIMARY KEY (user_id_, group_id_);


--
-- Name: act_id_priv_mapping act_id_priv_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_priv_mapping
    ADD CONSTRAINT act_id_priv_mapping_pkey PRIMARY KEY (id_);


--
-- Name: act_id_priv act_id_priv_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_priv
    ADD CONSTRAINT act_id_priv_pkey PRIMARY KEY (id_);


--
-- Name: act_id_property act_id_property_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_property
    ADD CONSTRAINT act_id_property_pkey PRIMARY KEY (name_);


--
-- Name: act_id_token act_id_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_token
    ADD CONSTRAINT act_id_token_pkey PRIMARY KEY (id_);


--
-- Name: act_id_user act_id_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_user
    ADD CONSTRAINT act_id_user_pkey PRIMARY KEY (id_);


--
-- Name: act_procdef_info act_procdef_info_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_procdef_info
    ADD CONSTRAINT act_procdef_info_pkey PRIMARY KEY (id_);


--
-- Name: act_re_deployment act_re_deployment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_re_deployment
    ADD CONSTRAINT act_re_deployment_pkey PRIMARY KEY (id_);


--
-- Name: act_re_model act_re_model_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_re_model
    ADD CONSTRAINT act_re_model_pkey PRIMARY KEY (id_);


--
-- Name: act_re_procdef act_re_procdef_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_re_procdef
    ADD CONSTRAINT act_re_procdef_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_actinst act_ru_actinst_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_actinst
    ADD CONSTRAINT act_ru_actinst_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_deadletter_job act_ru_deadletter_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_deadletter_job
    ADD CONSTRAINT act_ru_deadletter_job_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_entitylink act_ru_entitylink_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_entitylink
    ADD CONSTRAINT act_ru_entitylink_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_event_subscr act_ru_event_subscr_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_event_subscr
    ADD CONSTRAINT act_ru_event_subscr_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_execution act_ru_execution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_execution
    ADD CONSTRAINT act_ru_execution_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_external_job act_ru_external_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_external_job
    ADD CONSTRAINT act_ru_external_job_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_history_job act_ru_history_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_history_job
    ADD CONSTRAINT act_ru_history_job_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_identitylink act_ru_identitylink_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_identitylink
    ADD CONSTRAINT act_ru_identitylink_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_job act_ru_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_job
    ADD CONSTRAINT act_ru_job_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_suspended_job act_ru_suspended_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_suspended_job
    ADD CONSTRAINT act_ru_suspended_job_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_task act_ru_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_task
    ADD CONSTRAINT act_ru_task_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_timer_job act_ru_timer_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_timer_job
    ADD CONSTRAINT act_ru_timer_job_pkey PRIMARY KEY (id_);


--
-- Name: act_ru_variable act_ru_variable_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_variable
    ADD CONSTRAINT act_ru_variable_pkey PRIMARY KEY (id_);


--
-- Name: act_procdef_info act_uniq_info_procdef; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_procdef_info
    ADD CONSTRAINT act_uniq_info_procdef UNIQUE (proc_def_id_);


--
-- Name: act_id_priv act_uniq_priv_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_priv
    ADD CONSTRAINT act_uniq_priv_name UNIQUE (name_);


--
-- Name: act_re_procdef act_uniq_procdef; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_re_procdef
    ADD CONSTRAINT act_uniq_procdef UNIQUE (key_, version_, derived_version_, tenant_id_);


--
-- Name: biz_famuuq8hfd_report biz_famuuq8hfd_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_famuuq8hfd_report
    ADD CONSTRAINT biz_famuuq8hfd_report_pkey PRIMARY KEY (id);


--
-- Name: biz_famuuqabke_rulepivot biz_famuuqabke_rulepivot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_famuuqabke_rulepivot
    ADD CONSTRAINT biz_famuuqabke_rulepivot_pkey PRIMARY KEY (id);


--
-- Name: biz_famuuqom2g_legacyreport biz_famuuqom2g_legacyreport_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_famuuqom2g_legacyreport
    ADD CONSTRAINT biz_famuuqom2g_legacyreport_pkey PRIMARY KEY (id);


--
-- Name: biz_object_cssjdx biz_object_cssjdx_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_cssjdx
    ADD CONSTRAINT biz_object_cssjdx_pkey PRIMARY KEY (id);


--
-- Name: biz_object_cszdyrqx biz_object_cszdyrqx_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_cszdyrqx
    ADD CONSTRAINT biz_object_cszdyrqx_pkey PRIMARY KEY (id);


--
-- Name: biz_object_gs biz_object_gs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_gs
    ADD CONSTRAINT biz_object_gs_pkey PRIMARY KEY (id);


--
-- Name: biz_object_kjkm biz_object_kjkm_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_kjkm
    ADD CONSTRAINT biz_object_kjkm_pkey PRIMARY KEY (id);


--
-- Name: biz_object_kjkmfl biz_object_kjkmfl_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_kjkmfl
    ADD CONSTRAINT biz_object_kjkmfl_pkey PRIMARY KEY (id);


--
-- Name: biz_object_kjpzlr biz_object_kjpzlr_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_kjpzlr
    ADD CONSTRAINT biz_object_kjpzlr_pkey PRIMARY KEY (id);


--
-- Name: biz_object_yhlb biz_object_yhlb_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_yhlb
    ADD CONSTRAINT biz_object_yhlb_pkey PRIMARY KEY (id);


--
-- Name: biz_object_yhls biz_object_yhls_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_yhls
    ADD CONSTRAINT biz_object_yhls_pkey PRIMARY KEY (id);


--
-- Name: biz_object_yhzh biz_object_yhzh_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_yhzh
    ADD CONSTRAINT biz_object_yhzh_pkey PRIMARY KEY (id);


--
-- Name: biz_test_b1_211261a740c2457dorderedperf biz_test_b1_211261a740c2457dorderedperf_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_test_b1_211261a740c2457dorderedperf
    ADD CONSTRAINT biz_test_b1_211261a740c2457dorderedperf_pkey PRIMARY KEY (id);


--
-- Name: biz_test_b1_4d33b085319540f3accounts biz_test_b1_4d33b085319540f3accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_test_b1_4d33b085319540f3accounts
    ADD CONSTRAINT biz_test_b1_4d33b085319540f3accounts_pkey PRIMARY KEY (id);


--
-- Name: biz_test_b1_4d33b085319540f3companies biz_test_b1_4d33b085319540f3companies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_test_b1_4d33b085319540f3companies
    ADD CONSTRAINT biz_test_b1_4d33b085319540f3companies_pkey PRIMARY KEY (id);


--
-- Name: biz_test_b1_4d33b085319540f3entries biz_test_b1_4d33b085319540f3entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_test_b1_4d33b085319540f3entries
    ADD CONSTRAINT biz_test_b1_4d33b085319540f3entries_pkey PRIMARY KEY (id);


--
-- Name: drive_entry_origin drive_entry_origin_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_entry_origin
    ADD CONSTRAINT drive_entry_origin_pkey PRIMARY KEY (entry_id);


--
-- Name: drive_entry drive_entry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_entry
    ADD CONSTRAINT drive_entry_pkey PRIMARY KEY (id);


--
-- Name: drive_permission drive_permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_permission
    ADD CONSTRAINT drive_permission_pkey PRIMARY KEY (id);


--
-- Name: drive_share drive_share_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_share
    ADD CONSTRAINT drive_share_pkey PRIMARY KEY (id);


--
-- Name: drive_share_subject drive_share_subject_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_share_subject
    ADD CONSTRAINT drive_share_subject_pkey PRIMARY KEY (id);


--
-- Name: drive_space drive_space_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_space
    ADD CONSTRAINT drive_space_pkey PRIMARY KEY (id);


--
-- Name: drive_storage_setting drive_storage_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_storage_setting
    ADD CONSTRAINT drive_storage_setting_pkey PRIMARY KEY (id);


--
-- Name: drive_user_mark drive_user_mark_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_user_mark
    ADD CONSTRAINT drive_user_mark_pkey PRIMARY KEY (id);


--
-- Name: flw_ru_batch_part flw_ru_batch_part_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_ru_batch_part
    ADD CONSTRAINT flw_ru_batch_part_pkey PRIMARY KEY (id_);


--
-- Name: flw_ru_batch flw_ru_batch_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_ru_batch
    ADD CONSTRAINT flw_ru_batch_pkey PRIMARY KEY (id_);


--
-- Name: nocode_application_access nocode_application_access_application_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_access
    ADD CONSTRAINT nocode_application_access_application_id_key UNIQUE (application_id);


--
-- Name: nocode_application_access nocode_application_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_access
    ADD CONSTRAINT nocode_application_access_pkey PRIMARY KEY (id);


--
-- Name: nocode_application nocode_application_app_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application
    ADD CONSTRAINT nocode_application_app_code_key UNIQUE (app_code);


--
-- Name: nocode_application_object_follow_log nocode_application_object_follow_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_object_follow_log
    ADD CONSTRAINT nocode_application_object_follow_log_pkey PRIMARY KEY (id);


--
-- Name: nocode_application_object_follow nocode_application_object_follow_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_object_follow
    ADD CONSTRAINT nocode_application_object_follow_pkey PRIMARY KEY (id);


--
-- Name: nocode_application_object_follow nocode_application_object_follow_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_object_follow
    ADD CONSTRAINT nocode_application_object_follow_uk UNIQUE (application_id, object_id);


--
-- Name: nocode_application nocode_application_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application
    ADD CONSTRAINT nocode_application_pkey PRIMARY KEY (id);


--
-- Name: nocode_application_version nocode_application_version_application_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_version
    ADD CONSTRAINT nocode_application_version_application_id_version_no_key UNIQUE (application_id, version_no);


--
-- Name: nocode_application_version nocode_application_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_version
    ADD CONSTRAINT nocode_application_version_pkey PRIMARY KEY (id);


--
-- Name: nocode_biz_attachment_binding nocode_biz_attachment_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_attachment_binding
    ADD CONSTRAINT nocode_biz_attachment_binding_pkey PRIMARY KEY (id);


--
-- Name: nocode_biz_attachment_binding nocode_biz_attachment_binding_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_attachment_binding
    ADD CONSTRAINT nocode_biz_attachment_binding_uk UNIQUE (object_id, record_id, detail_id, row_id, field_id, file_id);


--
-- Name: nocode_biz_directory_binding nocode_biz_directory_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_directory_binding
    ADD CONSTRAINT nocode_biz_directory_binding_pkey PRIMARY KEY (id);


--
-- Name: nocode_biz_directory_binding nocode_biz_directory_binding_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_directory_binding
    ADD CONSTRAINT nocode_biz_directory_binding_uk UNIQUE (object_id, record_id, detail_id, row_id, field_id);


--
-- Name: nocode_biz_file_mark nocode_biz_file_mark_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_file_mark
    ADD CONSTRAINT nocode_biz_file_mark_pkey PRIMARY KEY (id);


--
-- Name: nocode_biz_file_retention nocode_biz_file_retention_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_file_retention
    ADD CONSTRAINT nocode_biz_file_retention_pkey PRIMARY KEY (id);


--
-- Name: nocode_biz_file_retention nocode_biz_file_retention_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_file_retention
    ADD CONSTRAINT nocode_biz_file_retention_uk UNIQUE (file_id, holder_type, holder_id);


--
-- Name: nocode_biz_file_task nocode_biz_file_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_file_task
    ADD CONSTRAINT nocode_biz_file_task_pkey PRIMARY KEY (id);


--
-- Name: nocode_biz_upload_session nocode_biz_upload_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_upload_session
    ADD CONSTRAINT nocode_biz_upload_session_pkey PRIMARY KEY (id);


--
-- Name: nocode_biz_upload_session nocode_biz_upload_session_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_biz_upload_session
    ADD CONSTRAINT nocode_biz_upload_session_uk UNIQUE (session_key, file_id);


--
-- Name: nocode_business_counter nocode_business_counter_object_id_field_id_period_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_business_counter
    ADD CONSTRAINT nocode_business_counter_object_id_field_id_period_key_key UNIQUE (object_id, field_id, period_key);


--
-- Name: nocode_business_counter nocode_business_counter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_business_counter
    ADD CONSTRAINT nocode_business_counter_pkey PRIMARY KEY (id);


--
-- Name: nocode_date_trigger_done nocode_date_trigger_done_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_date_trigger_done
    ADD CONSTRAINT nocode_date_trigger_done_pkey PRIMARY KEY (id);


--
-- Name: nocode_date_trigger_done nocode_date_trigger_done_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_date_trigger_done
    ADD CONSTRAINT nocode_date_trigger_done_uk UNIQUE (application_id, resource_id, business_date, source_record_id);


--
-- Name: nocode_date_trigger_state nocode_date_trigger_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_date_trigger_state
    ADD CONSTRAINT nocode_date_trigger_state_pkey PRIMARY KEY (id);


--
-- Name: nocode_date_trigger_state nocode_date_trigger_state_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_date_trigger_state
    ADD CONSTRAINT nocode_date_trigger_state_uk UNIQUE (application_id, resource_id);


--
-- Name: nocode_deployment nocode_deployment_object_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_deployment
    ADD CONSTRAINT nocode_deployment_object_id_version_no_key UNIQUE (object_id, version_no);


--
-- Name: nocode_deployment nocode_deployment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_deployment
    ADD CONSTRAINT nocode_deployment_pkey PRIMARY KEY (id);


--
-- Name: nocode_detail_position nocode_detail_position_object_id_detail_id_parent_id_record_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_detail_position
    ADD CONSTRAINT nocode_detail_position_object_id_detail_id_parent_id_record_key UNIQUE (object_id, detail_id, parent_id, record_id);


--
-- Name: nocode_detail_position nocode_detail_position_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_detail_position
    ADD CONSTRAINT nocode_detail_position_pkey PRIMARY KEY (id);


--
-- Name: nocode_document_receipt nocode_document_receipt_creator_application_id_object_id_op_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_document_receipt
    ADD CONSTRAINT nocode_document_receipt_creator_application_id_object_id_op_key UNIQUE (creator, application_id, object_id, operation, request_key);


--
-- Name: nocode_document_receipt nocode_document_receipt_operation_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_document_receipt
    ADD CONSTRAINT nocode_document_receipt_operation_id_key UNIQUE (operation_id);


--
-- Name: nocode_document_receipt nocode_document_receipt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_document_receipt
    ADD CONSTRAINT nocode_document_receipt_pkey PRIMARY KEY (id);


--
-- Name: nocode_field nocode_field_object_table_id_column_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_field
    ADD CONSTRAINT nocode_field_object_table_id_column_name_key UNIQUE (object_table_id, column_name) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: nocode_field nocode_field_object_table_id_field_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_field
    ADD CONSTRAINT nocode_field_object_table_id_field_code_key UNIQUE (object_table_id, field_code) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: nocode_field nocode_field_object_version_id_stable_field_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_field
    ADD CONSTRAINT nocode_field_object_version_id_stable_field_id_key UNIQUE (object_version_id, stable_field_id);


--
-- Name: nocode_field nocode_field_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_field
    ADD CONSTRAINT nocode_field_pkey PRIMARY KEY (id);


--
-- Name: nocode_flow_task_binding nocode_flow_task_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_flow_task_binding
    ADD CONSTRAINT nocode_flow_task_binding_pkey PRIMARY KEY (task_id);


--
-- Name: nocode_flow_task_binding nocode_flow_task_binding_submission_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_flow_task_binding
    ADD CONSTRAINT nocode_flow_task_binding_submission_uk UNIQUE (submission_id);


--
-- Name: nocode_handling_request nocode_handling_request_creator_application_id_object_id_re_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_handling_request
    ADD CONSTRAINT nocode_handling_request_creator_application_id_object_id_re_key UNIQUE (creator, application_id, object_id, request_key);


--
-- Name: nocode_handling_request nocode_handling_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_handling_request
    ADD CONSTRAINT nocode_handling_request_pkey PRIMARY KEY (id);


--
-- Name: nocode_index_definition nocode_index_definition_object_version_id_index_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_index_definition
    ADD CONSTRAINT nocode_index_definition_object_version_id_index_code_key UNIQUE (object_version_id, index_code);


--
-- Name: nocode_index_definition nocode_index_definition_object_version_id_stable_index_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_index_definition
    ADD CONSTRAINT nocode_index_definition_object_version_id_stable_index_id_key UNIQUE (object_version_id, stable_index_id);


--
-- Name: nocode_index_definition nocode_index_definition_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_index_definition
    ADD CONSTRAINT nocode_index_definition_pkey PRIMARY KEY (id);


--
-- Name: nocode_linkage_trigger nocode_linkage_trigger_field; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_linkage_trigger
    ADD CONSTRAINT nocode_linkage_trigger_field UNIQUE (application_id, application_version, target_object_id, target_field_id);


--
-- Name: nocode_linkage_trigger nocode_linkage_trigger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_linkage_trigger
    ADD CONSTRAINT nocode_linkage_trigger_pkey PRIMARY KEY (id);


--
-- Name: nocode_object_application_grant_log nocode_object_application_gra_object_id_application_id_lock_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant_log
    ADD CONSTRAINT nocode_object_application_gra_object_id_application_id_lock_key UNIQUE (object_id, application_id, lock_version);


--
-- Name: nocode_object_application_grant_log nocode_object_application_grant_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant_log
    ADD CONSTRAINT nocode_object_application_grant_log_pkey PRIMARY KEY (id);


--
-- Name: nocode_object_application_grant nocode_object_application_grant_object_id_application_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant
    ADD CONSTRAINT nocode_object_application_grant_object_id_application_id_key UNIQUE (object_id, application_id);


--
-- Name: nocode_object_application_grant nocode_object_application_grant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant
    ADD CONSTRAINT nocode_object_application_grant_pkey PRIMARY KEY (id);


--
-- Name: nocode_object nocode_object_object_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object
    ADD CONSTRAINT nocode_object_object_code_key UNIQUE (object_code);


--
-- Name: nocode_object nocode_object_physical_name_seed_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object
    ADD CONSTRAINT nocode_object_physical_name_seed_key UNIQUE (physical_name_seed);


--
-- Name: nocode_object nocode_object_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object
    ADD CONSTRAINT nocode_object_pkey PRIMARY KEY (id);


--
-- Name: nocode_object_table nocode_object_table_object_version_id_stable_table_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_table
    ADD CONSTRAINT nocode_object_table_object_version_id_stable_table_id_key UNIQUE (object_version_id, stable_table_id);


--
-- Name: nocode_object_table nocode_object_table_object_version_id_table_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_table
    ADD CONSTRAINT nocode_object_table_object_version_id_table_code_key UNIQUE (object_version_id, table_code);


--
-- Name: nocode_object_table nocode_object_table_object_version_id_table_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_table
    ADD CONSTRAINT nocode_object_table_object_version_id_table_name_key UNIQUE (object_version_id, table_name);


--
-- Name: nocode_object_table nocode_object_table_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_table
    ADD CONSTRAINT nocode_object_table_pkey PRIMARY KEY (id);


--
-- Name: nocode_object_version nocode_object_version_object_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_version
    ADD CONSTRAINT nocode_object_version_object_id_version_no_key UNIQUE (object_id, version_no);


--
-- Name: nocode_object_version nocode_object_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_version
    ADD CONSTRAINT nocode_object_version_pkey PRIMARY KEY (id);


--
-- Name: nocode_operation_log nocode_operation_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_operation_log
    ADD CONSTRAINT nocode_operation_log_pkey PRIMARY KEY (id);


--
-- Name: nocode_ordered_calculation_state nocode_ordered_calculation_state_field; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_ordered_calculation_state
    ADD CONSTRAINT nocode_ordered_calculation_state_field UNIQUE (object_id, field_id);


--
-- Name: nocode_ordered_calculation_state nocode_ordered_calculation_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_ordered_calculation_state
    ADD CONSTRAINT nocode_ordered_calculation_state_pkey PRIMARY KEY (id);


--
-- Name: nocode_publish_plan nocode_publish_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_publish_plan
    ADD CONSTRAINT nocode_publish_plan_pkey PRIMARY KEY (id);


--
-- Name: nocode_record_folder_binding nocode_record_folder_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_folder_binding
    ADD CONSTRAINT nocode_record_folder_binding_pkey PRIMARY KEY (id);


--
-- Name: nocode_record_folder_source nocode_record_folder_source_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_folder_source
    ADD CONSTRAINT nocode_record_folder_source_pkey PRIMARY KEY (id);


--
-- Name: nocode_record_history_head nocode_record_history_head_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_history_head
    ADD CONSTRAINT nocode_record_history_head_pkey PRIMARY KEY (object_id);


--
-- Name: nocode_record_history nocode_record_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_history
    ADD CONSTRAINT nocode_record_history_pkey PRIMARY KEY (id);


--
-- Name: nocode_record_process nocode_record_process_business_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_process
    ADD CONSTRAINT nocode_record_process_business_key_key UNIQUE (business_key);


--
-- Name: nocode_record_process nocode_record_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_process
    ADD CONSTRAINT nocode_record_process_pkey PRIMARY KEY (id);


--
-- Name: nocode_record_process nocode_record_process_process_instance_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_process
    ADD CONSTRAINT nocode_record_process_process_instance_id_key UNIQUE (process_instance_id);


--
-- Name: nocode_relation nocode_relation_object_version_id_relation_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_relation
    ADD CONSTRAINT nocode_relation_object_version_id_relation_code_key UNIQUE (object_version_id, relation_code);


--
-- Name: nocode_relation nocode_relation_object_version_id_stable_relation_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_relation
    ADD CONSTRAINT nocode_relation_object_version_id_stable_relation_id_key UNIQUE (object_version_id, stable_relation_id);


--
-- Name: nocode_relation nocode_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_relation
    ADD CONSTRAINT nocode_relation_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_dashboard nocode_report_dashboard_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dashboard
    ADD CONSTRAINT nocode_report_dashboard_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_dashboard_version nocode_report_dashboard_versi_dashboard_id_creator_request__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dashboard_version
    ADD CONSTRAINT nocode_report_dashboard_versi_dashboard_id_creator_request__key UNIQUE (dashboard_id, creator, request_id);


--
-- Name: nocode_report_dashboard_version nocode_report_dashboard_version_dashboard_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dashboard_version
    ADD CONSTRAINT nocode_report_dashboard_version_dashboard_id_version_no_key UNIQUE (dashboard_id, version_no);


--
-- Name: nocode_report_dashboard_version nocode_report_dashboard_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dashboard_version
    ADD CONSTRAINT nocode_report_dashboard_version_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_dataset nocode_report_dataset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset
    ADD CONSTRAINT nocode_report_dataset_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_dataset_policy nocode_report_dataset_policy_dataset_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset_policy
    ADD CONSTRAINT nocode_report_dataset_policy_dataset_id_key UNIQUE (dataset_id);


--
-- Name: nocode_report_dataset_policy nocode_report_dataset_policy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset_policy
    ADD CONSTRAINT nocode_report_dataset_policy_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_dataset_version nocode_report_dataset_version_dataset_id_creator_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset_version
    ADD CONSTRAINT nocode_report_dataset_version_dataset_id_creator_request_id_key UNIQUE (dataset_id, creator, request_id);


--
-- Name: nocode_report_dataset_version nocode_report_dataset_version_dataset_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset_version
    ADD CONSTRAINT nocode_report_dataset_version_dataset_id_version_no_key UNIQUE (dataset_id, version_no);


--
-- Name: nocode_report_dataset_version nocode_report_dataset_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset_version
    ADD CONSTRAINT nocode_report_dataset_version_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_dependency nocode_report_dependency_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dependency
    ADD CONSTRAINT nocode_report_dependency_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_dependency nocode_report_dependency_source_kind_source_id_source_stage_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dependency
    ADD CONSTRAINT nocode_report_dependency_source_kind_source_id_source_stage_key UNIQUE (source_kind, source_id, source_stage, source_version, target_kind, target_id, target_version);


--
-- Name: nocode_report_folder nocode_report_folder_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_folder
    ADD CONSTRAINT nocode_report_folder_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_object_grant nocode_report_object_grant_dataset_id_object_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_object_grant
    ADD CONSTRAINT nocode_report_object_grant_dataset_id_object_id_key UNIQUE (dataset_id, object_id);


--
-- Name: nocode_report_object_grant nocode_report_object_grant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_object_grant
    ADD CONSTRAINT nocode_report_object_grant_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_operation_log nocode_report_operation_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_operation_log
    ADD CONSTRAINT nocode_report_operation_log_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_preference nocode_report_preference_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_preference
    ADD CONSTRAINT nocode_report_preference_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_preference nocode_report_preference_user_id_dashboard_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_preference
    ADD CONSTRAINT nocode_report_preference_user_id_dashboard_id_key UNIQUE (user_id, dashboard_id);


--
-- Name: nocode_report_resource_acl nocode_report_resource_acl_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_resource_acl
    ADD CONSTRAINT nocode_report_resource_acl_pkey PRIMARY KEY (id);


--
-- Name: nocode_report_resource_acl nocode_report_resource_acl_resource_kind_resource_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_resource_acl
    ADD CONSTRAINT nocode_report_resource_acl_resource_kind_resource_id_key UNIQUE (resource_kind, resource_id);


--
-- Name: nocode_resource_dependency nocode_resource_dependency_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_resource_dependency
    ADD CONSTRAINT nocode_resource_dependency_pkey PRIMARY KEY (id);


--
-- Name: nocode_resource_dependency nocode_resource_dependency_source_kind_source_key_target_ob_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_resource_dependency
    ADD CONSTRAINT nocode_resource_dependency_source_kind_source_key_target_ob_key UNIQUE (source_kind, source_key, target_object_id);


--
-- Name: nocode_task_comment nocode_task_comment_creator_request_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_comment
    ADD CONSTRAINT nocode_task_comment_creator_request_key_key UNIQUE (creator, request_key);


--
-- Name: nocode_task_comment nocode_task_comment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_comment
    ADD CONSTRAINT nocode_task_comment_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_entry_access nocode_task_entry_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_access
    ADD CONSTRAINT nocode_task_entry_access_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_entry_access nocode_task_entry_access_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_access
    ADD CONSTRAINT nocode_task_entry_access_unique UNIQUE (application_id, entry_id);


--
-- Name: nocode_task_entry_binding nocode_task_entry_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_binding
    ADD CONSTRAINT nocode_task_entry_binding_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_entry_binding nocode_task_entry_binding_task_id_entry_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_binding
    ADD CONSTRAINT nocode_task_entry_binding_task_id_entry_key_key UNIQUE (task_id, entry_key);


--
-- Name: nocode_task_entry_record nocode_task_entry_record_creator_request_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_record
    ADD CONSTRAINT nocode_task_entry_record_creator_request_key_key UNIQUE (creator, request_key);


--
-- Name: nocode_task_entry_record nocode_task_entry_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_record
    ADD CONSTRAINT nocode_task_entry_record_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_entry_template_version nocode_task_entry_template_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_template_version
    ADD CONSTRAINT nocode_task_entry_template_version_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_entry_template_version nocode_task_entry_template_version_template_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_template_version
    ADD CONSTRAINT nocode_task_entry_template_version_template_id_version_no_key UNIQUE (template_id, version_no);


--
-- Name: nocode_task_event nocode_task_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_event
    ADD CONSTRAINT nocode_task_event_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_instance nocode_task_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_instance
    ADD CONSTRAINT nocode_task_instance_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_launch_draft nocode_task_launch_draft_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_launch_draft
    ADD CONSTRAINT nocode_task_launch_draft_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_plan nocode_task_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_plan
    ADD CONSTRAINT nocode_task_plan_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_record_link nocode_task_record_link_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_record_link
    ADD CONSTRAINT nocode_task_record_link_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_record_link nocode_task_record_link_task_id_application_id_object_id_re_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_record_link
    ADD CONSTRAINT nocode_task_record_link_task_id_application_id_object_id_re_key UNIQUE (task_id, application_id, object_id, record_id);


--
-- Name: nocode_task_template nocode_task_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_template
    ADD CONSTRAINT nocode_task_template_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_template_version nocode_task_template_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_template_version
    ADD CONSTRAINT nocode_task_template_version_pkey PRIMARY KEY (id);


--
-- Name: nocode_task_template_version nocode_task_template_version_template_id_version_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_template_version
    ADD CONSTRAINT nocode_task_template_version_template_id_version_no_key UNIQUE (template_id, version_no);


--
-- Name: nocode_work_draft nocode_work_draft_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_draft
    ADD CONSTRAINT nocode_work_draft_pkey PRIMARY KEY (id);


--
-- Name: nocode_work_event nocode_work_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_event
    ADD CONSTRAINT nocode_work_event_pkey PRIMARY KEY (id);


--
-- Name: nocode_work_event nocode_work_event_submission_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_event
    ADD CONSTRAINT nocode_work_event_submission_uk UNIQUE (event_type, submission_id);


--
-- Name: nocode_work_submission nocode_work_submission_command_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_submission
    ADD CONSTRAINT nocode_work_submission_command_uk UNIQUE (creator, idempotency_key);


--
-- Name: nocode_work_submission nocode_work_submission_draft_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_submission
    ADD CONSTRAINT nocode_work_submission_draft_uk UNIQUE (draft_id);


--
-- Name: nocode_work_submission nocode_work_submission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_submission
    ADD CONSTRAINT nocode_work_submission_pkey PRIMARY KEY (id);


--
-- Name: nocode_workflow_task_node nocode_workflow_task_node_execution_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_workflow_task_node
    ADD CONSTRAINT nocode_workflow_task_node_execution_uk UNIQUE (execution_id, process_instance_id, node_id);


--
-- Name: nocode_workflow_task_node nocode_workflow_task_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_workflow_task_node
    ADD CONSTRAINT nocode_workflow_task_node_pkey PRIMARY KEY (id);


--
-- Name: nocode_workflow_task_node nocode_workflow_task_node_task_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_workflow_task_node
    ADD CONSTRAINT nocode_workflow_task_node_task_uk UNIQUE (task_id);


--
-- Name: bpm_category pk_bpm_category; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bpm_category
    ADD CONSTRAINT pk_bpm_category PRIMARY KEY (id);


--
-- Name: bpm_form pk_bpm_form; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bpm_form
    ADD CONSTRAINT pk_bpm_form PRIMARY KEY (id);


--
-- Name: bpm_oa_leave pk_bpm_oa_leave; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bpm_oa_leave
    ADD CONSTRAINT pk_bpm_oa_leave PRIMARY KEY (id);


--
-- Name: bpm_process_definition_info pk_bpm_process_definition_info; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bpm_process_definition_info
    ADD CONSTRAINT pk_bpm_process_definition_info PRIMARY KEY (id);


--
-- Name: bpm_process_expression pk_bpm_process_expression; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bpm_process_expression
    ADD CONSTRAINT pk_bpm_process_expression PRIMARY KEY (id);


--
-- Name: infra_file_config pk_infra_file_config; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_file_config
    ADD CONSTRAINT pk_infra_file_config PRIMARY KEY (id);


--
-- Name: infra_file_content pk_infra_file_content; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_file_content
    ADD CONSTRAINT pk_infra_file_content PRIMARY KEY (id);


--
-- Name: infra_job pk_infra_job; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_job
    ADD CONSTRAINT pk_infra_job PRIMARY KEY (id);


--
-- Name: infra_job_log pk_infra_job_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.infra_job_log
    ADD CONSTRAINT pk_infra_job_log PRIMARY KEY (id);


--
-- Name: system_dept pk_system_dept; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_dept
    ADD CONSTRAINT pk_system_dept PRIMARY KEY (id);


--
-- Name: system_dict_data pk_system_dict_data; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_dict_data
    ADD CONSTRAINT pk_system_dict_data PRIMARY KEY (id);


--
-- Name: system_dict_type pk_system_dict_type; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_dict_type
    ADD CONSTRAINT pk_system_dict_type PRIMARY KEY (id);


--
-- Name: system_login_log pk_system_login_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_login_log
    ADD CONSTRAINT pk_system_login_log PRIMARY KEY (id);


--
-- Name: system_mail_account pk_system_mail_account; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_mail_account
    ADD CONSTRAINT pk_system_mail_account PRIMARY KEY (id);


--
-- Name: system_mail_log pk_system_mail_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_mail_log
    ADD CONSTRAINT pk_system_mail_log PRIMARY KEY (id);


--
-- Name: system_mail_template pk_system_mail_template; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_mail_template
    ADD CONSTRAINT pk_system_mail_template PRIMARY KEY (id);


--
-- Name: system_menu pk_system_menu; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_menu
    ADD CONSTRAINT pk_system_menu PRIMARY KEY (id);


--
-- Name: system_notice pk_system_notice; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_notice
    ADD CONSTRAINT pk_system_notice PRIMARY KEY (id);


--
-- Name: system_notify_message pk_system_notify_message; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_notify_message
    ADD CONSTRAINT pk_system_notify_message PRIMARY KEY (id);


--
-- Name: system_notify_template pk_system_notify_template; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_notify_template
    ADD CONSTRAINT pk_system_notify_template PRIMARY KEY (id);


--
-- Name: system_oauth2_access_token pk_system_oauth2_access_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_oauth2_access_token
    ADD CONSTRAINT pk_system_oauth2_access_token PRIMARY KEY (id);


--
-- Name: system_oauth2_approve pk_system_oauth2_approve; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_oauth2_approve
    ADD CONSTRAINT pk_system_oauth2_approve PRIMARY KEY (id);


--
-- Name: system_oauth2_client pk_system_oauth2_client; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_oauth2_client
    ADD CONSTRAINT pk_system_oauth2_client PRIMARY KEY (id);


--
-- Name: system_oauth2_code pk_system_oauth2_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_oauth2_code
    ADD CONSTRAINT pk_system_oauth2_code PRIMARY KEY (id);


--
-- Name: system_oauth2_refresh_token pk_system_oauth2_refresh_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_oauth2_refresh_token
    ADD CONSTRAINT pk_system_oauth2_refresh_token PRIMARY KEY (id);


--
-- Name: system_operate_log pk_system_operate_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_operate_log
    ADD CONSTRAINT pk_system_operate_log PRIMARY KEY (id);


--
-- Name: system_post pk_system_post; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_post
    ADD CONSTRAINT pk_system_post PRIMARY KEY (id);


--
-- Name: system_role pk_system_role; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_role
    ADD CONSTRAINT pk_system_role PRIMARY KEY (id);


--
-- Name: system_role_menu pk_system_role_menu; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_role_menu
    ADD CONSTRAINT pk_system_role_menu PRIMARY KEY (id);


--
-- Name: system_sms_channel pk_system_sms_channel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_sms_channel
    ADD CONSTRAINT pk_system_sms_channel PRIMARY KEY (id);


--
-- Name: system_sms_code pk_system_sms_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_sms_code
    ADD CONSTRAINT pk_system_sms_code PRIMARY KEY (id);


--
-- Name: system_sms_log pk_system_sms_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_sms_log
    ADD CONSTRAINT pk_system_sms_log PRIMARY KEY (id);


--
-- Name: system_social_client pk_system_social_client; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_social_client
    ADD CONSTRAINT pk_system_social_client PRIMARY KEY (id);


--
-- Name: system_social_user pk_system_social_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_social_user
    ADD CONSTRAINT pk_system_social_user PRIMARY KEY (id);


--
-- Name: system_social_user_bind pk_system_social_user_bind; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_social_user_bind
    ADD CONSTRAINT pk_system_social_user_bind PRIMARY KEY (id);


--
-- Name: system_tenant pk_system_tenant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_tenant
    ADD CONSTRAINT pk_system_tenant PRIMARY KEY (id);


--
-- Name: system_tenant_package pk_system_tenant_package; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_tenant_package
    ADD CONSTRAINT pk_system_tenant_package PRIMARY KEY (id);


--
-- Name: system_user_dept pk_system_user_dept; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_user_dept
    ADD CONSTRAINT pk_system_user_dept PRIMARY KEY (id);


--
-- Name: system_user_post pk_system_user_post; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_user_post
    ADD CONSTRAINT pk_system_user_post PRIMARY KEY (id);


--
-- Name: system_user_role pk_system_user_role; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_user_role
    ADD CONSTRAINT pk_system_user_role PRIMARY KEY (id);


--
-- Name: system_users pk_system_users; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_users
    ADD CONSTRAINT pk_system_users PRIMARY KEY (id);


--
-- Name: yudao_demo01_contact pk_yudao_demo01_contact; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yudao_demo01_contact
    ADD CONSTRAINT pk_yudao_demo01_contact PRIMARY KEY (id);


--
-- Name: yudao_demo02_category pk_yudao_demo02_category; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yudao_demo02_category
    ADD CONSTRAINT pk_yudao_demo02_category PRIMARY KEY (id);


--
-- Name: yudao_demo03_course pk_yudao_demo03_course; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yudao_demo03_course
    ADD CONSTRAINT pk_yudao_demo03_course PRIMARY KEY (id);


--
-- Name: yudao_demo03_grade pk_yudao_demo03_grade; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yudao_demo03_grade
    ADD CONSTRAINT pk_yudao_demo03_grade PRIMARY KEY (id);


--
-- Name: yudao_demo03_student pk_yudao_demo03_student; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yudao_demo03_student
    ADD CONSTRAINT pk_yudao_demo03_student PRIMARY KEY (id);


--
-- Name: project_role project_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_role
    ADD CONSTRAINT project_role_pkey PRIMARY KEY (id);


--
-- Name: qrtz_blob_triggers qrtz_blob_triggers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_blob_triggers
    ADD CONSTRAINT qrtz_blob_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_calendars qrtz_calendars_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_calendars
    ADD CONSTRAINT qrtz_calendars_pkey PRIMARY KEY (sched_name, calendar_name);


--
-- Name: qrtz_cron_triggers qrtz_cron_triggers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_cron_triggers
    ADD CONSTRAINT qrtz_cron_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_fired_triggers qrtz_fired_triggers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_fired_triggers
    ADD CONSTRAINT qrtz_fired_triggers_pkey PRIMARY KEY (sched_name, entry_id);


--
-- Name: qrtz_job_details qrtz_job_details_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_job_details
    ADD CONSTRAINT qrtz_job_details_pkey PRIMARY KEY (sched_name, job_name, job_group);


--
-- Name: qrtz_locks qrtz_locks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_locks
    ADD CONSTRAINT qrtz_locks_pkey PRIMARY KEY (sched_name, lock_name);


--
-- Name: qrtz_paused_trigger_grps qrtz_paused_trigger_grps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_paused_trigger_grps
    ADD CONSTRAINT qrtz_paused_trigger_grps_pkey PRIMARY KEY (sched_name, trigger_group);


--
-- Name: qrtz_scheduler_state qrtz_scheduler_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_scheduler_state
    ADD CONSTRAINT qrtz_scheduler_state_pkey PRIMARY KEY (sched_name, instance_name);


--
-- Name: qrtz_simple_triggers qrtz_simple_triggers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_simple_triggers
    ADD CONSTRAINT qrtz_simple_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simprop_triggers qrtz_simprop_triggers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_simprop_triggers
    ADD CONSTRAINT qrtz_simprop_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_triggers qrtz_triggers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.qrtz_triggers
    ADD CONSTRAINT qrtz_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: sys_msg_notice_log sys_msg_notice_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_msg_notice_log
    ADD CONSTRAINT sys_msg_notice_log_pkey PRIMARY KEY (id);


--
-- Name: sys_msg_notice sys_msg_notice_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_msg_notice
    ADD CONSTRAINT sys_msg_notice_pkey PRIMARY KEY (id);


--
-- Name: sys_msg sys_msg_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_msg
    ADD CONSTRAINT sys_msg_pkey PRIMARY KEY (id);


--
-- Name: sys_msg_subscribe sys_msg_subscribe_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_msg_subscribe
    ADD CONSTRAINT sys_msg_subscribe_pkey PRIMARY KEY (id);


--
-- Name: sys_msg_targets sys_msg_targets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_msg_targets
    ADD CONSTRAINT sys_msg_targets_pkey PRIMARY KEY (id);


--
-- Name: sys_msg_template_target sys_msg_template_target_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_msg_template_target
    ADD CONSTRAINT sys_msg_template_target_pkey PRIMARY KEY (id);


--
-- Name: sys_msg_template sys_msg_type_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_msg_template
    ADD CONSTRAINT sys_msg_type_pkey PRIMARY KEY (id);


--
-- Name: system_feedback_follow_up system_feedback_follow_up_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_feedback_follow_up
    ADD CONSTRAINT system_feedback_follow_up_pkey PRIMARY KEY (id);


--
-- Name: system_feedback system_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_feedback
    ADD CONSTRAINT system_feedback_pkey PRIMARY KEY (id);


--
-- Name: system_feedback_reference system_feedback_reference_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_feedback_reference
    ADD CONSTRAINT system_feedback_reference_pkey PRIMARY KEY (id);


--
-- Name: scaffold_component uq_scaffold_component_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scaffold_component
    ADD CONSTRAINT uq_scaffold_component_version_key UNIQUE (version_id, component_key);


--
-- Name: scaffold_version uq_scaffold_version_scaffold_tag; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scaffold_version
    ADD CONSTRAINT uq_scaffold_version_scaffold_tag UNIQUE (scaffold_id, version_tag);


--
-- Name: act_idx_act_hi_tsk_log_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_act_hi_tsk_log_task ON public.act_hi_tsk_log USING btree (task_id_);


--
-- Name: act_idx_athrz_procedef; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_athrz_procedef ON public.act_ru_identitylink USING btree (proc_def_id_);


--
-- Name: act_idx_bytear_depl; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_bytear_depl ON public.act_ge_bytearray USING btree (deployment_id_);


--
-- Name: act_idx_channel_def_uniq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX act_idx_channel_def_uniq ON public.flw_channel_definition USING btree (key_, version_, tenant_id_);


--
-- Name: act_idx_deadletter_job_correlation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_deadletter_job_correlation_id ON public.act_ru_deadletter_job USING btree (correlation_id_);


--
-- Name: act_idx_deadletter_job_custom_values_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_deadletter_job_custom_values_id ON public.act_ru_deadletter_job USING btree (custom_values_id_);


--
-- Name: act_idx_deadletter_job_exception_stack_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_deadletter_job_exception_stack_id ON public.act_ru_deadletter_job USING btree (exception_stack_id_);


--
-- Name: act_idx_deadletter_job_execution_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_deadletter_job_execution_id ON public.act_ru_deadletter_job USING btree (execution_id_);


--
-- Name: act_idx_deadletter_job_proc_def_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_deadletter_job_proc_def_id ON public.act_ru_deadletter_job USING btree (proc_def_id_);


--
-- Name: act_idx_deadletter_job_process_instance_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_deadletter_job_process_instance_id ON public.act_ru_deadletter_job USING btree (process_instance_id_);


--
-- Name: act_idx_djob_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_djob_scope ON public.act_ru_deadletter_job USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_djob_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_djob_scope_def ON public.act_ru_deadletter_job USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_djob_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_djob_sub_scope ON public.act_ru_deadletter_job USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_ejob_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ejob_scope ON public.act_ru_external_job USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_ejob_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ejob_scope_def ON public.act_ru_external_job USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_ejob_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ejob_sub_scope ON public.act_ru_external_job USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_ent_lnk_ref_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ent_lnk_ref_scope ON public.act_ru_entitylink USING btree (ref_scope_id_, ref_scope_type_, link_type_);


--
-- Name: act_idx_ent_lnk_root_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ent_lnk_root_scope ON public.act_ru_entitylink USING btree (root_scope_id_, root_scope_type_, link_type_);


--
-- Name: act_idx_ent_lnk_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ent_lnk_scope ON public.act_ru_entitylink USING btree (scope_id_, scope_type_, link_type_);


--
-- Name: act_idx_ent_lnk_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ent_lnk_scope_def ON public.act_ru_entitylink USING btree (scope_definition_id_, scope_type_, link_type_);


--
-- Name: act_idx_event_def_uniq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX act_idx_event_def_uniq ON public.flw_event_definition USING btree (key_, version_, tenant_id_);


--
-- Name: act_idx_event_subscr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_event_subscr ON public.act_ru_event_subscr USING btree (execution_id_);


--
-- Name: act_idx_event_subscr_config_; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_event_subscr_config_ ON public.act_ru_event_subscr USING btree (configuration_);


--
-- Name: act_idx_event_subscr_proc_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_event_subscr_proc_id ON public.act_ru_event_subscr USING btree (proc_inst_id_);


--
-- Name: act_idx_event_subscr_scoperef_; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_event_subscr_scoperef_ ON public.act_ru_event_subscr USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_exe_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_exe_parent ON public.act_ru_execution USING btree (parent_id_);


--
-- Name: act_idx_exe_procdef; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_exe_procdef ON public.act_ru_execution USING btree (proc_def_id_);


--
-- Name: act_idx_exe_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_exe_procinst ON public.act_ru_execution USING btree (proc_inst_id_);


--
-- Name: act_idx_exe_root; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_exe_root ON public.act_ru_execution USING btree (root_proc_inst_id_);


--
-- Name: act_idx_exe_super; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_exe_super ON public.act_ru_execution USING btree (super_exec_);


--
-- Name: act_idx_exec_buskey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_exec_buskey ON public.act_ru_execution USING btree (business_key_);


--
-- Name: act_idx_exec_ref_id_; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_exec_ref_id_ ON public.act_ru_execution USING btree (reference_id_);


--
-- Name: act_idx_external_job_correlation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_external_job_correlation_id ON public.act_ru_external_job USING btree (correlation_id_);


--
-- Name: act_idx_external_job_custom_values_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_external_job_custom_values_id ON public.act_ru_external_job USING btree (custom_values_id_);


--
-- Name: act_idx_external_job_exception_stack_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_external_job_exception_stack_id ON public.act_ru_external_job USING btree (exception_stack_id_);


--
-- Name: act_idx_hi_act_inst_end; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_act_inst_end ON public.act_hi_actinst USING btree (end_time_);


--
-- Name: act_idx_hi_act_inst_exec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_act_inst_exec ON public.act_hi_actinst USING btree (execution_id_, act_id_);


--
-- Name: act_idx_hi_act_inst_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_act_inst_procinst ON public.act_hi_actinst USING btree (proc_inst_id_, act_id_);


--
-- Name: act_idx_hi_act_inst_start; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_act_inst_start ON public.act_hi_actinst USING btree (start_time_);


--
-- Name: act_idx_hi_detail_act_inst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_detail_act_inst ON public.act_hi_detail USING btree (act_inst_id_);


--
-- Name: act_idx_hi_detail_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_detail_name ON public.act_hi_detail USING btree (name_);


--
-- Name: act_idx_hi_detail_proc_inst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_detail_proc_inst ON public.act_hi_detail USING btree (proc_inst_id_);


--
-- Name: act_idx_hi_detail_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_detail_task_id ON public.act_hi_detail USING btree (task_id_);


--
-- Name: act_idx_hi_detail_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_detail_time ON public.act_hi_detail USING btree (time_);


--
-- Name: act_idx_hi_ent_lnk_ref_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ent_lnk_ref_scope ON public.act_hi_entitylink USING btree (ref_scope_id_, ref_scope_type_, link_type_);


--
-- Name: act_idx_hi_ent_lnk_root_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ent_lnk_root_scope ON public.act_hi_entitylink USING btree (root_scope_id_, root_scope_type_, link_type_);


--
-- Name: act_idx_hi_ent_lnk_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ent_lnk_scope ON public.act_hi_entitylink USING btree (scope_id_, scope_type_, link_type_);


--
-- Name: act_idx_hi_ent_lnk_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ent_lnk_scope_def ON public.act_hi_entitylink USING btree (scope_definition_id_, scope_type_, link_type_);


--
-- Name: act_idx_hi_ident_lnk_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ident_lnk_procinst ON public.act_hi_identitylink USING btree (proc_inst_id_);


--
-- Name: act_idx_hi_ident_lnk_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ident_lnk_scope ON public.act_hi_identitylink USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_hi_ident_lnk_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ident_lnk_scope_def ON public.act_hi_identitylink USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_hi_ident_lnk_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ident_lnk_sub_scope ON public.act_hi_identitylink USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_hi_ident_lnk_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ident_lnk_task ON public.act_hi_identitylink USING btree (task_id_);


--
-- Name: act_idx_hi_ident_lnk_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_ident_lnk_user ON public.act_hi_identitylink USING btree (user_id_);


--
-- Name: act_idx_hi_pro_i_buskey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_pro_i_buskey ON public.act_hi_procinst USING btree (business_key_);


--
-- Name: act_idx_hi_pro_inst_end; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_pro_inst_end ON public.act_hi_procinst USING btree (end_time_);


--
-- Name: act_idx_hi_pro_super_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_pro_super_procinst ON public.act_hi_procinst USING btree (super_process_instance_id_);


--
-- Name: act_idx_hi_procvar_exe; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_procvar_exe ON public.act_hi_varinst USING btree (execution_id_);


--
-- Name: act_idx_hi_procvar_name_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_procvar_name_type ON public.act_hi_varinst USING btree (name_, var_type_);


--
-- Name: act_idx_hi_procvar_proc_inst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_procvar_proc_inst ON public.act_hi_varinst USING btree (proc_inst_id_);


--
-- Name: act_idx_hi_procvar_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_procvar_task_id ON public.act_hi_varinst USING btree (task_id_);


--
-- Name: act_idx_hi_task_inst_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_task_inst_procinst ON public.act_hi_taskinst USING btree (proc_inst_id_);


--
-- Name: act_idx_hi_task_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_task_scope ON public.act_hi_taskinst USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_hi_task_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_task_scope_def ON public.act_hi_taskinst USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_hi_task_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_task_sub_scope ON public.act_hi_taskinst USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_hi_var_scope_id_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_var_scope_id_type ON public.act_hi_varinst USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_hi_var_sub_id_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_hi_var_sub_id_type ON public.act_hi_varinst USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_ident_lnk_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ident_lnk_group ON public.act_ru_identitylink USING btree (group_id_);


--
-- Name: act_idx_ident_lnk_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ident_lnk_scope ON public.act_ru_identitylink USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_ident_lnk_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ident_lnk_scope_def ON public.act_ru_identitylink USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_ident_lnk_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ident_lnk_sub_scope ON public.act_ru_identitylink USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_ident_lnk_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ident_lnk_user ON public.act_ru_identitylink USING btree (user_id_);


--
-- Name: act_idx_idl_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_idl_procinst ON public.act_ru_identitylink USING btree (proc_inst_id_);


--
-- Name: act_idx_job_correlation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_correlation_id ON public.act_ru_job USING btree (correlation_id_);


--
-- Name: act_idx_job_custom_values_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_custom_values_id ON public.act_ru_job USING btree (custom_values_id_);


--
-- Name: act_idx_job_exception_stack_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_exception_stack_id ON public.act_ru_job USING btree (exception_stack_id_);


--
-- Name: act_idx_job_execution_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_execution_id ON public.act_ru_job USING btree (execution_id_);


--
-- Name: act_idx_job_proc_def_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_proc_def_id ON public.act_ru_job USING btree (proc_def_id_);


--
-- Name: act_idx_job_process_instance_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_process_instance_id ON public.act_ru_job USING btree (process_instance_id_);


--
-- Name: act_idx_job_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_scope ON public.act_ru_job USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_job_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_scope_def ON public.act_ru_job USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_job_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_job_sub_scope ON public.act_ru_job USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_memb_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_memb_group ON public.act_id_membership USING btree (group_id_);


--
-- Name: act_idx_memb_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_memb_user ON public.act_id_membership USING btree (user_id_);


--
-- Name: act_idx_model_deployment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_model_deployment ON public.act_re_model USING btree (deployment_id_);


--
-- Name: act_idx_model_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_model_source ON public.act_re_model USING btree (editor_source_value_id_);


--
-- Name: act_idx_model_source_extra; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_model_source_extra ON public.act_re_model USING btree (editor_source_extra_value_id_);


--
-- Name: act_idx_priv_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_priv_group ON public.act_id_priv_mapping USING btree (group_id_);


--
-- Name: act_idx_priv_mapping; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_priv_mapping ON public.act_id_priv_mapping USING btree (priv_id_);


--
-- Name: act_idx_priv_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_priv_user ON public.act_id_priv_mapping USING btree (user_id_);


--
-- Name: act_idx_procdef_info_json; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_procdef_info_json ON public.act_procdef_info USING btree (info_json_id_);


--
-- Name: act_idx_procdef_info_proc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_procdef_info_proc ON public.act_procdef_info USING btree (proc_def_id_);


--
-- Name: act_idx_ru_acti_end; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_acti_end ON public.act_ru_actinst USING btree (end_time_);


--
-- Name: act_idx_ru_acti_exec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_acti_exec ON public.act_ru_actinst USING btree (execution_id_);


--
-- Name: act_idx_ru_acti_exec_act; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_acti_exec_act ON public.act_ru_actinst USING btree (execution_id_, act_id_);


--
-- Name: act_idx_ru_acti_proc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_acti_proc ON public.act_ru_actinst USING btree (proc_inst_id_);


--
-- Name: act_idx_ru_acti_proc_act; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_acti_proc_act ON public.act_ru_actinst USING btree (proc_inst_id_, act_id_);


--
-- Name: act_idx_ru_acti_start; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_acti_start ON public.act_ru_actinst USING btree (start_time_);


--
-- Name: act_idx_ru_acti_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_acti_task ON public.act_ru_actinst USING btree (task_id_);


--
-- Name: act_idx_ru_var_scope_id_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_var_scope_id_type ON public.act_ru_variable USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_ru_var_sub_id_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_ru_var_sub_id_type ON public.act_ru_variable USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_sjob_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_sjob_scope ON public.act_ru_suspended_job USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_sjob_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_sjob_scope_def ON public.act_ru_suspended_job USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_sjob_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_sjob_sub_scope ON public.act_ru_suspended_job USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_suspended_job_correlation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_suspended_job_correlation_id ON public.act_ru_suspended_job USING btree (correlation_id_);


--
-- Name: act_idx_suspended_job_custom_values_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_suspended_job_custom_values_id ON public.act_ru_suspended_job USING btree (custom_values_id_);


--
-- Name: act_idx_suspended_job_exception_stack_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_suspended_job_exception_stack_id ON public.act_ru_suspended_job USING btree (exception_stack_id_);


--
-- Name: act_idx_suspended_job_execution_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_suspended_job_execution_id ON public.act_ru_suspended_job USING btree (execution_id_);


--
-- Name: act_idx_suspended_job_proc_def_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_suspended_job_proc_def_id ON public.act_ru_suspended_job USING btree (proc_def_id_);


--
-- Name: act_idx_suspended_job_process_instance_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_suspended_job_process_instance_id ON public.act_ru_suspended_job USING btree (process_instance_id_);


--
-- Name: act_idx_task_create; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_task_create ON public.act_ru_task USING btree (create_time_);


--
-- Name: act_idx_task_exec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_task_exec ON public.act_ru_task USING btree (execution_id_);


--
-- Name: act_idx_task_procdef; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_task_procdef ON public.act_ru_task USING btree (proc_def_id_);


--
-- Name: act_idx_task_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_task_procinst ON public.act_ru_task USING btree (proc_inst_id_);


--
-- Name: act_idx_task_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_task_scope ON public.act_ru_task USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_task_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_task_scope_def ON public.act_ru_task USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_task_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_task_sub_scope ON public.act_ru_task USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_timer_job_correlation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_timer_job_correlation_id ON public.act_ru_timer_job USING btree (correlation_id_);


--
-- Name: act_idx_timer_job_custom_values_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_timer_job_custom_values_id ON public.act_ru_timer_job USING btree (custom_values_id_);


--
-- Name: act_idx_timer_job_duedate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_timer_job_duedate ON public.act_ru_timer_job USING btree (duedate_);


--
-- Name: act_idx_timer_job_exception_stack_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_timer_job_exception_stack_id ON public.act_ru_timer_job USING btree (exception_stack_id_);


--
-- Name: act_idx_timer_job_execution_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_timer_job_execution_id ON public.act_ru_timer_job USING btree (execution_id_);


--
-- Name: act_idx_timer_job_proc_def_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_timer_job_proc_def_id ON public.act_ru_timer_job USING btree (proc_def_id_);


--
-- Name: act_idx_timer_job_process_instance_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_timer_job_process_instance_id ON public.act_ru_timer_job USING btree (process_instance_id_);


--
-- Name: act_idx_tjob_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_tjob_scope ON public.act_ru_timer_job USING btree (scope_id_, scope_type_);


--
-- Name: act_idx_tjob_scope_def; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_tjob_scope_def ON public.act_ru_timer_job USING btree (scope_definition_id_, scope_type_);


--
-- Name: act_idx_tjob_sub_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_tjob_sub_scope ON public.act_ru_timer_job USING btree (sub_scope_id_, scope_type_);


--
-- Name: act_idx_tskass_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_tskass_task ON public.act_ru_identitylink USING btree (task_id_);


--
-- Name: act_idx_var_bytearray; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_var_bytearray ON public.act_ru_variable USING btree (bytearray_id_);


--
-- Name: act_idx_var_exe; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_var_exe ON public.act_ru_variable USING btree (execution_id_);


--
-- Name: act_idx_var_procinst; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_var_procinst ON public.act_ru_variable USING btree (proc_inst_id_);


--
-- Name: act_idx_variable_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX act_idx_variable_task_id ON public.act_ru_variable USING btree (task_id_);


--
-- Name: drive_entry_file_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_entry_file_idx ON public.drive_entry USING btree (file_id) WHERE (deleted = 0);


--
-- Name: drive_entry_file_name_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX drive_entry_file_name_uk ON public.drive_entry USING btree (space_id, parent_id, name) WHERE ((deleted = 0) AND ((type)::text = 'FILE'::text) AND ((trash_state)::text = 'NORMAL'::text));


--
-- Name: drive_entry_folder_name_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX drive_entry_folder_name_uk ON public.drive_entry USING btree (space_id, parent_id, name) WHERE ((deleted = 0) AND ((type)::text = 'FOLDER'::text) AND ((trash_state)::text = 'NORMAL'::text));


--
-- Name: drive_entry_origin_key_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_entry_origin_key_idx ON public.drive_entry_origin USING btree (origin_key) WHERE (deleted = 0);


--
-- Name: drive_entry_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_entry_parent_idx ON public.drive_entry USING btree (space_id, parent_id, trash_state, name) WHERE (deleted = 0);


--
-- Name: drive_permission_subject_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_permission_subject_idx ON public.drive_permission USING btree (subject_type, subject_id) WHERE (deleted = 0);


--
-- Name: drive_permission_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX drive_permission_uk ON public.drive_permission USING btree (space_id, entry_id, subject_type, subject_id) WHERE (deleted = 0);


--
-- Name: drive_share_creator_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_share_creator_idx ON public.drive_share USING btree (creator, status) WHERE (deleted = 0);


--
-- Name: drive_share_entry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_share_entry_idx ON public.drive_share USING btree (entry_id, status) WHERE (deleted = 0);


--
-- Name: drive_share_subject_subject_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_share_subject_subject_idx ON public.drive_share_subject USING btree (subject_type, subject_id) WHERE (deleted = 0);


--
-- Name: drive_share_subject_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX drive_share_subject_uk ON public.drive_share_subject USING btree (share_id, subject_type, subject_id) WHERE (deleted = 0);


--
-- Name: drive_space_biz_name_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX drive_space_biz_name_uk ON public.drive_space USING btree (name) WHERE (((type)::text = 'BIZ'::text) AND (deleted = 0));


--
-- Name: drive_space_personal_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX drive_space_personal_uk ON public.drive_space USING btree (owner_id) WHERE (((type)::text = 'PERSONAL'::text) AND (deleted = 0));


--
-- Name: drive_space_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_space_type_idx ON public.drive_space USING btree (type, status) WHERE (deleted = 0);


--
-- Name: drive_user_mark_recent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX drive_user_mark_recent_idx ON public.drive_user_mark USING btree (user_id, mark_type, access_time DESC) WHERE (deleted = 0);


--
-- Name: drive_user_mark_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX drive_user_mark_uk ON public.drive_user_mark USING btree (user_id, entry_id, mark_type) WHERE (deleted = 0);


--
-- Name: flw_idx_batch_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flw_idx_batch_part ON public.flw_ru_batch_part USING btree (batch_id_);


--
-- Name: flw_idx_event_rsrc_dpl; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flw_idx_event_rsrc_dpl ON public.flw_event_resource USING btree (deployment_id_);


--
-- Name: idx_bpm_oa_leave_process_instance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bpm_oa_leave_process_instance ON public.bpm_oa_leave USING btree (process_instance_id);


--
-- Name: idx_bpm_oa_leave_status_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bpm_oa_leave_status_type ON public.bpm_oa_leave USING btree (status, type, deleted);


--
-- Name: idx_bpm_oa_leave_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bpm_oa_leave_user ON public.bpm_oa_leave USING btree (user_id, create_time, deleted);


--
-- Name: idx_feedback_follow_up_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feedback_follow_up_time ON public.system_feedback_follow_up USING btree (tenant_id, feedback_id, create_time);


--
-- Name: idx_feedback_reference_feedback; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feedback_reference_feedback ON public.system_feedback_reference USING btree (tenant_id, feedback_id);


--
-- Name: idx_infra_file_content_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_file_content_01 ON public.infra_file_content USING btree (config_id, path);


--
-- Name: idx_infra_job_log_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_job_log_01 ON public.infra_job_log USING btree (job_id);


--
-- Name: idx_infra_job_log_02; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_infra_job_log_02 ON public.infra_job_log USING btree (create_time);


--
-- Name: idx_msg_tpl_target_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_tpl_target_lookup ON public.sys_msg_template_target USING btree (tenant_id, msg_template_id);


--
-- Name: idx_scaffold_component_version_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scaffold_component_version_id ON public.scaffold_component USING btree (version_id) WHERE (deleted = 0);


--
-- Name: idx_scaffold_template_language; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scaffold_template_language ON public.scaffold_template USING btree (language) WHERE (deleted = 0);


--
-- Name: idx_scaffold_template_org_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scaffold_template_org_id ON public.scaffold_template USING btree (org_id) WHERE (deleted = 0);


--
-- Name: idx_scaffold_version_scaffold_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scaffold_version_scaffold_id ON public.scaffold_version USING btree (scaffold_id) WHERE (deleted = 0);


--
-- Name: idx_system_feedback_assignee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_feedback_assignee ON public.system_feedback USING btree (tenant_id, assignee_id, status);


--
-- Name: idx_system_feedback_status_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_feedback_status_time ON public.system_feedback USING btree (tenant_id, status, create_time);


--
-- Name: idx_system_feedback_submitter; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_feedback_submitter ON public.system_feedback USING btree (tenant_id, submitter_id);


--
-- Name: idx_system_feedback_tenant_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_feedback_tenant_time ON public.system_feedback USING btree (tenant_id, create_time);


--
-- Name: idx_system_login_log_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_login_log_01 ON public.system_login_log USING btree (username);


--
-- Name: idx_system_login_log_02; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_login_log_02 ON public.system_login_log USING btree (create_time);


--
-- Name: idx_system_notify_message_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_notify_message_01 ON public.system_notify_message USING btree (user_id, user_type, read_status);


--
-- Name: idx_system_oauth2_access_token_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_oauth2_access_token_01 ON public.system_oauth2_access_token USING btree (access_token);


--
-- Name: idx_system_oauth2_access_token_02; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_oauth2_access_token_02 ON public.system_oauth2_access_token USING btree (refresh_token);


--
-- Name: idx_system_oauth2_approve_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_oauth2_approve_01 ON public.system_oauth2_approve USING btree (user_id, user_type, client_id);


--
-- Name: idx_system_oauth2_client_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_oauth2_client_01 ON public.system_oauth2_client USING btree (client_id);


--
-- Name: idx_system_oauth2_code_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_oauth2_code_01 ON public.system_oauth2_code USING btree (code);


--
-- Name: idx_system_oauth2_refresh_token_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_oauth2_refresh_token_01 ON public.system_oauth2_refresh_token USING btree (refresh_token);


--
-- Name: idx_system_operate_log_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_operate_log_01 ON public.system_operate_log USING btree (user_id);


--
-- Name: idx_system_operate_log_02; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_operate_log_02 ON public.system_operate_log USING btree (create_time);


--
-- Name: idx_system_role_menu_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_role_menu_01 ON public.system_role_menu USING btree (role_id);


--
-- Name: idx_system_sms_code_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_sms_code_01 ON public.system_sms_code USING btree (mobile);


--
-- Name: idx_system_social_user_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_social_user_01 ON public.system_social_user USING btree (type, openid);


--
-- Name: idx_system_social_user_02; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_social_user_02 ON public.system_social_user USING btree (type, code, state);


--
-- Name: idx_system_social_user_bind_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_social_user_bind_01 ON public.system_social_user_bind USING btree (user_type, social_user_id);


--
-- Name: idx_system_user_dept_dept_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_user_dept_dept_id ON public.system_user_dept USING btree (tenant_id, dept_id) WHERE (deleted = 0);


--
-- Name: idx_system_user_role_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_user_role_01 ON public.system_user_role USING btree (user_id);


--
-- Name: idx_system_users_01; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_users_01 ON public.system_users USING btree (username);


--
-- Name: idx_system_users_02; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_users_02 ON public.system_users USING btree (mobile);


--
-- Name: idx_system_users_03; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_users_03 ON public.system_users USING btree (email);


--
-- Name: idx_system_users_04; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_system_users_04 ON public.system_users USING btree (dept_id);


--
-- Name: infra_file_protected_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX infra_file_protected_idx ON public.infra_file USING btree (config_id, path) WHERE (protected_flag AND (deleted = 0));


--
-- Name: nocode_application_category_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_application_category_idx ON public.nocode_application USING btree (category, creator) WHERE (deleted = 0);


--
-- Name: nocode_application_object_follow_log_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_application_object_follow_log_app ON public.nocode_application_object_follow_log USING btree (application_id, object_id, id);


--
-- Name: nocode_application_object_follow_log_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_application_object_follow_log_plan ON public.nocode_application_object_follow_log USING btree (plan_id) WHERE (plan_id IS NOT NULL);


--
-- Name: nocode_application_object_follow_object; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_application_object_follow_object ON public.nocode_application_object_follow USING btree (object_id);


--
-- Name: nocode_application_object_follow_pending_ix; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_application_object_follow_pending_ix ON public.nocode_application_object_follow USING btree (state) WHERE (((state)::text = 'PENDING'::text) AND (deleted = 0));


--
-- Name: nocode_application_recycle_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_application_recycle_idx ON public.nocode_application USING btree (deleted_at DESC, id DESC) WHERE (deleted = 1);


--
-- Name: nocode_biz_attachment_binding_browse_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_attachment_binding_browse_idx ON public.nocode_biz_attachment_binding USING btree (object_id, state, record_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_attachment_binding_file_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_attachment_binding_file_idx ON public.nocode_biz_attachment_binding USING btree (file_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_attachment_binding_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_attachment_binding_record_idx ON public.nocode_biz_attachment_binding USING btree (object_id, record_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_attachment_binding_state_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_attachment_binding_state_idx ON public.nocode_biz_attachment_binding USING btree (file_id, state) WHERE (deleted = 0);


--
-- Name: nocode_biz_directory_binding_entry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_directory_binding_entry_idx ON public.nocode_biz_directory_binding USING btree (space_id, entry_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_directory_binding_group_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_directory_binding_group_idx ON public.nocode_biz_directory_binding USING btree (object_id, rule_version, group_keys) WHERE ((deleted = 0) AND ((detail_id)::text = ''::text) AND ((row_id)::text = ''::text) AND ((field_id)::text = ''::text));


--
-- Name: nocode_biz_directory_binding_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_directory_binding_record_idx ON public.nocode_biz_directory_binding USING btree (object_id, record_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_file_mark_list_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_file_mark_list_idx ON public.nocode_biz_file_mark USING btree (user_id, object_id, mark_type, access_time DESC) WHERE (deleted = 0);


--
-- Name: nocode_biz_file_mark_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_biz_file_mark_uk ON public.nocode_biz_file_mark USING btree (user_id, entry_id, mark_type) WHERE (deleted = 0);


--
-- Name: nocode_biz_file_retention_file_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_file_retention_file_idx ON public.nocode_biz_file_retention USING btree (file_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_file_retention_holder_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_file_retention_holder_idx ON public.nocode_biz_file_retention USING btree (holder_type, holder_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_file_task_state_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_file_task_state_idx ON public.nocode_biz_file_task USING btree (task_type, state) WHERE (deleted = 0);


--
-- Name: nocode_biz_upload_session_expire_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_upload_session_expire_idx ON public.nocode_biz_upload_session USING btree (state, expires_at) WHERE (deleted = 0);


--
-- Name: nocode_biz_upload_session_file_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_biz_upload_session_file_idx ON public.nocode_biz_upload_session USING btree (file_id) WHERE (deleted = 0);


--
-- Name: nocode_biz_upload_session_idem_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_biz_upload_session_idem_uk ON public.nocode_biz_upload_session USING btree (user_id, object_id, field_id, session_key, idempotency_key) WHERE ((deleted = 0) AND ((state)::text = ANY (ARRAY[('TEMPORARY'::character varying)::text, ('BINDING'::character varying)::text])) AND ((idempotency_key)::text <> ''::text));


--
-- Name: nocode_date_trigger_done_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_date_trigger_done_date ON public.nocode_date_trigger_done USING btree (business_date);


--
-- Name: nocode_detail_position_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_detail_position_parent_idx ON public.nocode_detail_position USING btree (object_id, detail_id, parent_id, "position") WHERE (deleted = 0);


--
-- Name: nocode_document_receipt_maintenance_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_document_receipt_maintenance_request ON public.nocode_document_receipt USING btree (creator, object_id, operation, request_key) WHERE (application_id IS NULL);


--
-- Name: nocode_document_receipt_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_document_receipt_record_idx ON public.nocode_document_receipt USING btree (object_id, record_id);


--
-- Name: nocode_field_stable_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_field_stable_version ON public.nocode_field USING btree (stable_field_id, object_version_id);


--
-- Name: nocode_flow_task_binding_instance_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_flow_task_binding_instance_idx ON public.nocode_flow_task_binding USING btree (((task_json ->> 'processInstanceId'::text))) WHERE (deleted = 0);


--
-- Name: nocode_handling_request_actor_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_handling_request_actor_idx ON public.nocode_handling_request USING btree (creator, status, create_time DESC, id) WHERE (deleted = 0);


--
-- Name: nocode_handling_request_instance_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_handling_request_instance_idx ON public.nocode_handling_request USING btree (process_instance_id) WHERE ((deleted = 0) AND (process_instance_id IS NOT NULL));


--
-- Name: nocode_handling_request_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_handling_request_record_idx ON public.nocode_handling_request USING btree (object_id, record_id, status) WHERE (deleted = 0);


--
-- Name: nocode_linkage_trigger_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_linkage_trigger_source ON public.nocode_linkage_trigger USING btree (application_id, application_version, source_object_id) WHERE (deleted = 0);


--
-- Name: nocode_linkage_trigger_source_any; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_linkage_trigger_source_any ON public.nocode_linkage_trigger USING btree (source_object_id) WHERE (deleted = 0);


--
-- Name: nocode_linkage_trigger_target_any; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_linkage_trigger_target_any ON public.nocode_linkage_trigger USING btree (target_object_id) WHERE (deleted = 0);


--
-- Name: nocode_object_application_grant_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_object_application_grant_app ON public.nocode_object_application_grant USING btree (application_id);


--
-- Name: nocode_object_category_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_object_category_idx ON public.nocode_object USING btree (category) WHERE (deleted = 0);


--
-- Name: nocode_object_one_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_object_one_draft ON public.nocode_object_version USING btree (object_id) WHERE ((state)::text = 'DRAFT'::text);


--
-- Name: nocode_object_one_main; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_object_one_main ON public.nocode_object_table USING btree (object_version_id) WHERE ((table_role)::text = 'MAIN'::text);


--
-- Name: nocode_operation_resource; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_operation_resource ON public.nocode_operation_log USING btree (resource_type, resource_id, occurred_at DESC);


--
-- Name: nocode_publish_plan_object; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_publish_plan_object ON public.nocode_publish_plan USING btree (object_id, create_time DESC);


--
-- Name: nocode_record_folder_binding_entry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_folder_binding_entry_idx ON public.nocode_record_folder_binding USING btree (entry_id) WHERE (deleted = 0);


--
-- Name: nocode_record_folder_binding_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_record_folder_binding_uk ON public.nocode_record_folder_binding USING btree (object_id, record_id, source_id, anchor_entry_id) WHERE (deleted = 0);


--
-- Name: nocode_record_folder_source_object_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_folder_source_object_idx ON public.nocode_record_folder_source USING btree (object_id, sort_no) WHERE (deleted = 0);


--
-- Name: nocode_record_folder_source_target_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_folder_source_target_idx ON public.nocode_record_folder_source USING btree (target_source_id) WHERE (deleted = 0);


--
-- Name: nocode_record_history_changes_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_history_changes_time_idx ON public.nocode_record_history USING btree (object_id, occurred_at, id) WHERE ((deleted = 0) AND ((operation)::text <> 'BASELINE'::text));


--
-- Name: nocode_record_history_operation_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_history_operation_idx ON public.nocode_record_history USING btree (operation_id) WHERE (operation_id IS NOT NULL);


--
-- Name: nocode_record_history_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_history_record_idx ON public.nocode_record_history USING btree (object_id, record_id, occurred_at DESC, id DESC);


--
-- Name: nocode_record_history_task_source_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_history_task_source_idx ON public.nocode_record_history USING btree (creator, application_id, ((source_json ->> 'entryId'::text)), id DESC) WHERE ((deleted = 0) AND ((source_json ->> 'kind'::text) = 'TASK_ENTRY'::text));


--
-- Name: nocode_record_history_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_history_time_idx ON public.nocode_record_history USING btree (object_id, occurred_at, id);


--
-- Name: nocode_record_process_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_record_process_application ON public.nocode_record_process USING btree (application_id, status) WHERE (deleted = 0);


--
-- Name: nocode_record_process_one_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_record_process_one_active ON public.nocode_record_process USING btree (object_id, record_id) WHERE (((status)::text = 'RUNNING'::text) AND (deleted = 0));


--
-- Name: nocode_relation_detail_source_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_relation_detail_source_idx ON public.nocode_relation USING btree (object_version_id, source_detail_id) WHERE (deleted = 0);


--
-- Name: nocode_relation_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_relation_target ON public.nocode_relation USING btree (target_object_id);


--
-- Name: nocode_report_dashboard_folder_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_dashboard_folder_idx ON public.nocode_report_dashboard USING btree (folder_id) WHERE (deleted = 0);


--
-- Name: nocode_report_dashboard_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_dashboard_owner_idx ON public.nocode_report_dashboard USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0);


--
-- Name: nocode_report_dashboard_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_dashboard_status_idx ON public.nocode_report_dashboard USING btree (status, update_time DESC, id DESC) WHERE (deleted = 0);


--
-- Name: nocode_report_dataset_folder_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_dataset_folder_idx ON public.nocode_report_dataset USING btree (folder_id) WHERE (deleted = 0);


--
-- Name: nocode_report_dataset_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_dataset_owner_idx ON public.nocode_report_dataset USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0);


--
-- Name: nocode_report_dependency_target_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_dependency_target_idx ON public.nocode_report_dependency USING btree (target_kind, target_id) WHERE (deleted = 0);


--
-- Name: nocode_report_folder_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_folder_parent_idx ON public.nocode_report_folder USING btree (resource_kind, parent_id) WHERE (deleted = 0);


--
-- Name: nocode_report_folder_sibling_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_report_folder_sibling_name_idx ON public.nocode_report_folder USING btree (resource_kind, COALESCE(parent_id, (0)::bigint), lower((name)::text)) WHERE (deleted = 0);


--
-- Name: nocode_report_operation_resource_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_operation_resource_idx ON public.nocode_report_operation_log USING btree (resource_kind, resource_id, id DESC);


--
-- Name: nocode_report_preference_favorite_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_preference_favorite_idx ON public.nocode_report_preference USING btree (user_id, update_time DESC, dashboard_id DESC) WHERE ((deleted = 0) AND favorite);


--
-- Name: nocode_report_preference_recent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_report_preference_recent_idx ON public.nocode_report_preference USING btree (user_id, last_visited_at DESC, dashboard_id DESC) WHERE ((deleted = 0) AND (last_visited_at IS NOT NULL));


--
-- Name: nocode_task_acceptor_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_acceptor_idx ON public.nocode_task_instance USING btree ((((config_json)::jsonb ->> 'acceptorId'::text)), status) WHERE ((deleted = 0) AND (parent_id IS NULL));


--
-- Name: nocode_task_assignee_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_assignee_idx ON public.nocode_task_instance USING btree (assignee_id, status, expected_end) WHERE (deleted = 0);


--
-- Name: nocode_task_checklist_membership_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_checklist_membership_idx ON public.nocode_task_plan USING btree (user_id, period, plan_date, task_id) WHERE ((deleted = 0) AND ((plan_mode)::text = 'CHECKLIST'::text));


--
-- Name: nocode_task_claimable_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_claimable_idx ON public.nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text));


--
-- Name: nocode_task_comment_task_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_comment_task_idx ON public.nocode_task_comment USING btree (task_id, create_time) WHERE (deleted = 0);


--
-- Name: nocode_task_entry_binding_dataset_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_entry_binding_dataset_idx ON public.nocode_task_entry_binding USING btree (dataset_id) WHERE (deleted = 0);


--
-- Name: nocode_task_entry_record_dataset_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_entry_record_dataset_idx ON public.nocode_task_entry_record USING btree (dataset_id, create_time) WHERE (deleted = 0);


--
-- Name: nocode_task_entry_record_request_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_entry_record_request_idx ON public.nocode_task_entry_record USING btree ((((business_json)::jsonb ->> 'requestId'::text))) WHERE (deleted = 0);


--
-- Name: nocode_task_entry_record_task_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_entry_record_task_idx ON public.nocode_task_entry_record USING btree (task_id) WHERE ((deleted = 0) AND (superseded_by IS NULL));


--
-- Name: nocode_task_event_actor_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_event_actor_idx ON public.nocode_task_event USING btree (creator, create_time DESC) WHERE (deleted = 0);


--
-- Name: nocode_task_event_request_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_task_event_request_uk ON public.nocode_task_event USING btree (creator, request_key) WHERE (request_key IS NOT NULL);


--
-- Name: nocode_task_event_root_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_event_root_idx ON public.nocode_task_event USING btree (root_id, create_time) WHERE (deleted = 0);


--
-- Name: nocode_task_instance_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_instance_application_idx ON public.nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0);


--
-- Name: nocode_task_instance_kind_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_instance_kind_idx ON public.nocode_task_instance USING btree (kind, assignee_id, status, expected_end) WHERE (deleted = 0);


--
-- Name: nocode_task_launch_draft_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_launch_draft_owner_idx ON public.nocode_task_launch_draft USING btree (creator, update_time DESC, id DESC) WHERE ((deleted = 0) AND (published_task_id IS NULL));


--
-- Name: nocode_task_plan_active_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_task_plan_active_uk ON public.nocode_task_plan USING btree (task_id, user_id, plan_mode, period, plan_date, end_date, source) WHERE (deleted = 0);


--
-- Name: nocode_task_plan_range_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_plan_range_idx ON public.nocode_task_plan USING btree (user_id, plan_date, end_date) WHERE (deleted = 0);


--
-- Name: nocode_task_plan_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_plan_user_idx ON public.nocode_task_plan USING btree (user_id, period, plan_date) WHERE (deleted = 0);


--
-- Name: nocode_task_record_link_context_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_record_link_context_idx ON public.nocode_task_record_link USING btree (application_id, object_id, record_id) WHERE (deleted = 0);


--
-- Name: nocode_task_request_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_task_request_uk ON public.nocode_task_instance USING btree (creator, request_key) WHERE (request_key IS NOT NULL);


--
-- Name: nocode_task_root_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_task_root_idx ON public.nocode_task_instance USING btree (root_id) WHERE (deleted = 0);


--
-- Name: nocode_work_draft_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_work_draft_owner_idx ON public.nocode_work_draft USING btree (creator, state, update_time DESC, id) WHERE (deleted = 0);


--
-- Name: nocode_work_draft_source_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_work_draft_source_idx ON public.nocode_work_draft USING btree (source_type, source_id) WHERE (deleted = 0);


--
-- Name: nocode_work_draft_task_entry_active_uk; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX nocode_work_draft_task_entry_active_uk ON public.nocode_work_draft USING btree (creator, source_id) WHERE ((deleted = 0) AND ((source_type)::text = 'TASK_ENTRY'::text) AND ((state)::text = 'DRAFT'::text));


--
-- Name: nocode_work_event_source_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_work_event_source_idx ON public.nocode_work_event USING btree (source_type, source_id, create_time, id) WHERE (deleted = 0);


--
-- Name: nocode_work_submission_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_work_submission_owner_idx ON public.nocode_work_submission USING btree (creator, create_time DESC, id) WHERE (deleted = 0);


--
-- Name: nocode_work_submission_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_work_submission_record_idx ON public.nocode_work_submission USING btree (((material_json ->> 'objectId'::text)), ((material_json ->> 'recordId'::text))) WHERE (deleted = 0);


--
-- Name: nocode_workflow_task_node_process_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_workflow_task_node_process_idx ON public.nocode_workflow_task_node USING btree (tenant_id, process_instance_id) WHERE (deleted = false);


--
-- Name: nocode_workflow_task_node_retry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nocode_workflow_task_node_retry_idx ON public.nocode_workflow_task_node USING btree (next_attempt_time) WHERE ((deleted = false) AND ((state)::text = ANY (ARRAY[('CREATING'::character varying)::text, ('WAITING'::character varying)::text])));


--
-- Name: uk_feedback_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_feedback_reference ON public.system_feedback_reference USING btree (tenant_id, feedback_id, reference_type, target_id, deleted);


--
-- Name: uk_msg_tpl_target; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_msg_tpl_target ON public.sys_msg_template_target USING btree (tenant_id, msg_template_id, target_type, target_id, deleted);


--
-- Name: uk_system_dept_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_system_dept_code ON public.system_dept USING btree (tenant_id, dept_code) WHERE (deleted = 0);


--
-- Name: uk_system_feedback_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_system_feedback_no ON public.system_feedback USING btree (tenant_id, feedback_no);


--
-- Name: uk_system_user_dept_main; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_system_user_dept_main ON public.system_user_dept USING btree (tenant_id, user_id) WHERE ((deleted = 0) AND (is_main = 1));


--
-- Name: uk_system_user_dept_user_dept; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_system_user_dept_user_dept ON public.system_user_dept USING btree (tenant_id, user_id, dept_id) WHERE (deleted = 0);


--
-- Name: act_ru_identitylink act_fk_athrz_procedef; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_identitylink
    ADD CONSTRAINT act_fk_athrz_procedef FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ge_bytearray act_fk_bytearr_depl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ge_bytearray
    ADD CONSTRAINT act_fk_bytearr_depl FOREIGN KEY (deployment_id_) REFERENCES public.act_re_deployment(id_);


--
-- Name: act_ru_deadletter_job act_fk_deadletter_job_custom_values; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_deadletter_job
    ADD CONSTRAINT act_fk_deadletter_job_custom_values FOREIGN KEY (custom_values_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_deadletter_job act_fk_deadletter_job_exception; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_deadletter_job
    ADD CONSTRAINT act_fk_deadletter_job_exception FOREIGN KEY (exception_stack_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_deadletter_job act_fk_deadletter_job_execution; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_deadletter_job
    ADD CONSTRAINT act_fk_deadletter_job_execution FOREIGN KEY (execution_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_deadletter_job act_fk_deadletter_job_proc_def; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_deadletter_job
    ADD CONSTRAINT act_fk_deadletter_job_proc_def FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ru_deadletter_job act_fk_deadletter_job_process_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_deadletter_job
    ADD CONSTRAINT act_fk_deadletter_job_process_instance FOREIGN KEY (process_instance_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_event_subscr act_fk_event_exec; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_event_subscr
    ADD CONSTRAINT act_fk_event_exec FOREIGN KEY (execution_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_execution act_fk_exe_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_execution
    ADD CONSTRAINT act_fk_exe_parent FOREIGN KEY (parent_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_execution act_fk_exe_procdef; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_execution
    ADD CONSTRAINT act_fk_exe_procdef FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ru_execution act_fk_exe_procinst; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_execution
    ADD CONSTRAINT act_fk_exe_procinst FOREIGN KEY (proc_inst_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_execution act_fk_exe_super; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_execution
    ADD CONSTRAINT act_fk_exe_super FOREIGN KEY (super_exec_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_external_job act_fk_external_job_custom_values; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_external_job
    ADD CONSTRAINT act_fk_external_job_custom_values FOREIGN KEY (custom_values_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_external_job act_fk_external_job_exception; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_external_job
    ADD CONSTRAINT act_fk_external_job_exception FOREIGN KEY (exception_stack_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_identitylink act_fk_idl_procinst; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_identitylink
    ADD CONSTRAINT act_fk_idl_procinst FOREIGN KEY (proc_inst_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_procdef_info act_fk_info_json_ba; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_procdef_info
    ADD CONSTRAINT act_fk_info_json_ba FOREIGN KEY (info_json_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_procdef_info act_fk_info_procdef; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_procdef_info
    ADD CONSTRAINT act_fk_info_procdef FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ru_job act_fk_job_custom_values; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_job
    ADD CONSTRAINT act_fk_job_custom_values FOREIGN KEY (custom_values_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_job act_fk_job_exception; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_job
    ADD CONSTRAINT act_fk_job_exception FOREIGN KEY (exception_stack_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_job act_fk_job_execution; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_job
    ADD CONSTRAINT act_fk_job_execution FOREIGN KEY (execution_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_job act_fk_job_proc_def; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_job
    ADD CONSTRAINT act_fk_job_proc_def FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ru_job act_fk_job_process_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_job
    ADD CONSTRAINT act_fk_job_process_instance FOREIGN KEY (process_instance_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_id_membership act_fk_memb_group; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_membership
    ADD CONSTRAINT act_fk_memb_group FOREIGN KEY (group_id_) REFERENCES public.act_id_group(id_);


--
-- Name: act_id_membership act_fk_memb_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_membership
    ADD CONSTRAINT act_fk_memb_user FOREIGN KEY (user_id_) REFERENCES public.act_id_user(id_);


--
-- Name: act_re_model act_fk_model_deployment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_re_model
    ADD CONSTRAINT act_fk_model_deployment FOREIGN KEY (deployment_id_) REFERENCES public.act_re_deployment(id_);


--
-- Name: act_re_model act_fk_model_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_re_model
    ADD CONSTRAINT act_fk_model_source FOREIGN KEY (editor_source_value_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_re_model act_fk_model_source_extra; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_re_model
    ADD CONSTRAINT act_fk_model_source_extra FOREIGN KEY (editor_source_extra_value_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_id_priv_mapping act_fk_priv_mapping; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_id_priv_mapping
    ADD CONSTRAINT act_fk_priv_mapping FOREIGN KEY (priv_id_) REFERENCES public.act_id_priv(id_);


--
-- Name: act_ru_suspended_job act_fk_suspended_job_custom_values; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_suspended_job
    ADD CONSTRAINT act_fk_suspended_job_custom_values FOREIGN KEY (custom_values_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_suspended_job act_fk_suspended_job_exception; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_suspended_job
    ADD CONSTRAINT act_fk_suspended_job_exception FOREIGN KEY (exception_stack_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_suspended_job act_fk_suspended_job_execution; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_suspended_job
    ADD CONSTRAINT act_fk_suspended_job_execution FOREIGN KEY (execution_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_suspended_job act_fk_suspended_job_proc_def; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_suspended_job
    ADD CONSTRAINT act_fk_suspended_job_proc_def FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ru_suspended_job act_fk_suspended_job_process_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_suspended_job
    ADD CONSTRAINT act_fk_suspended_job_process_instance FOREIGN KEY (process_instance_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_task act_fk_task_exe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_task
    ADD CONSTRAINT act_fk_task_exe FOREIGN KEY (execution_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_task act_fk_task_procdef; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_task
    ADD CONSTRAINT act_fk_task_procdef FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ru_task act_fk_task_procinst; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_task
    ADD CONSTRAINT act_fk_task_procinst FOREIGN KEY (proc_inst_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_timer_job act_fk_timer_job_custom_values; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_timer_job
    ADD CONSTRAINT act_fk_timer_job_custom_values FOREIGN KEY (custom_values_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_timer_job act_fk_timer_job_exception; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_timer_job
    ADD CONSTRAINT act_fk_timer_job_exception FOREIGN KEY (exception_stack_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_timer_job act_fk_timer_job_execution; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_timer_job
    ADD CONSTRAINT act_fk_timer_job_execution FOREIGN KEY (execution_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_timer_job act_fk_timer_job_proc_def; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_timer_job
    ADD CONSTRAINT act_fk_timer_job_proc_def FOREIGN KEY (proc_def_id_) REFERENCES public.act_re_procdef(id_);


--
-- Name: act_ru_timer_job act_fk_timer_job_process_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_timer_job
    ADD CONSTRAINT act_fk_timer_job_process_instance FOREIGN KEY (process_instance_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_identitylink act_fk_tskass_task; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_identitylink
    ADD CONSTRAINT act_fk_tskass_task FOREIGN KEY (task_id_) REFERENCES public.act_ru_task(id_);


--
-- Name: act_ru_variable act_fk_var_bytearray; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_variable
    ADD CONSTRAINT act_fk_var_bytearray FOREIGN KEY (bytearray_id_) REFERENCES public.act_ge_bytearray(id_);


--
-- Name: act_ru_variable act_fk_var_exe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_variable
    ADD CONSTRAINT act_fk_var_exe FOREIGN KEY (execution_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: act_ru_variable act_fk_var_procinst; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.act_ru_variable
    ADD CONSTRAINT act_fk_var_procinst FOREIGN KEY (proc_inst_id_) REFERENCES public.act_ru_execution(id_);


--
-- Name: drive_entry drive_entry_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_entry
    ADD CONSTRAINT drive_entry_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.drive_space(id);


--
-- Name: drive_permission drive_permission_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_permission
    ADD CONSTRAINT drive_permission_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.drive_space(id);


--
-- Name: drive_share drive_share_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_share
    ADD CONSTRAINT drive_share_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.drive_space(id);


--
-- Name: drive_share_subject drive_share_subject_share_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drive_share_subject
    ADD CONSTRAINT drive_share_subject_share_id_fkey FOREIGN KEY (share_id) REFERENCES public.drive_share(id);


--
-- Name: flw_ru_batch_part flw_fk_batch_part_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_ru_batch_part
    ADD CONSTRAINT flw_fk_batch_part_parent FOREIGN KEY (batch_id_) REFERENCES public.flw_ru_batch(id_);


--
-- Name: flw_event_resource flw_fk_event_rsrc_dpl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flw_event_resource
    ADD CONSTRAINT flw_fk_event_rsrc_dpl FOREIGN KEY (deployment_id_) REFERENCES public.flw_event_deployment(id_);


--
-- Name: nocode_application_access nocode_application_access_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_access
    ADD CONSTRAINT nocode_application_access_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id);


--
-- Name: nocode_application_object_follow nocode_application_object_follow_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_object_follow
    ADD CONSTRAINT nocode_application_object_follow_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id) ON DELETE CASCADE;


--
-- Name: nocode_application_object_follow_log nocode_application_object_follow_log_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_object_follow_log
    ADD CONSTRAINT nocode_application_object_follow_log_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id) ON DELETE CASCADE;


--
-- Name: nocode_application_object_follow_log nocode_application_object_follow_log_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_object_follow_log
    ADD CONSTRAINT nocode_application_object_follow_log_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id) ON DELETE CASCADE;


--
-- Name: nocode_application_object_follow nocode_application_object_follow_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_object_follow
    ADD CONSTRAINT nocode_application_object_follow_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id) ON DELETE CASCADE;


--
-- Name: nocode_application_version nocode_application_version_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_application_version
    ADD CONSTRAINT nocode_application_version_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id);


--
-- Name: nocode_date_trigger_done nocode_date_trigger_done_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_date_trigger_done
    ADD CONSTRAINT nocode_date_trigger_done_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id) ON DELETE CASCADE;


--
-- Name: nocode_date_trigger_state nocode_date_trigger_state_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_date_trigger_state
    ADD CONSTRAINT nocode_date_trigger_state_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id) ON DELETE CASCADE;


--
-- Name: nocode_deployment nocode_deployment_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_deployment
    ADD CONSTRAINT nocode_deployment_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_detail_position nocode_detail_position_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_detail_position
    ADD CONSTRAINT nocode_detail_position_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id) ON DELETE CASCADE;


--
-- Name: nocode_document_receipt nocode_document_receipt_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_document_receipt
    ADD CONSTRAINT nocode_document_receipt_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id) ON DELETE CASCADE;


--
-- Name: nocode_field nocode_field_object_table_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_field
    ADD CONSTRAINT nocode_field_object_table_id_fkey FOREIGN KEY (object_table_id) REFERENCES public.nocode_object_table(id);


--
-- Name: nocode_field nocode_field_object_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_field
    ADD CONSTRAINT nocode_field_object_version_id_fkey FOREIGN KEY (object_version_id) REFERENCES public.nocode_object_version(id);


--
-- Name: biz_object_kjkm nocode_fk_r_34673; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_kjkm
    ADD CONSTRAINT nocode_fk_r_34673 FOREIGN KEY (c_kjkmfl_id) REFERENCES public.biz_object_kjkmfl(id) ON DELETE RESTRICT;


--
-- Name: biz_object_yhzh nocode_fk_r_35455; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_yhzh
    ADD CONSTRAINT nocode_fk_r_35455 FOREIGN KEY (c_yhmc_id) REFERENCES public.biz_object_yhlb(id) ON DELETE RESTRICT;


--
-- Name: biz_object_yhzh nocode_fk_r_35457; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_yhzh
    ADD CONSTRAINT nocode_fk_r_35457 FOREIGN KEY (c_gs_id) REFERENCES public.biz_object_gs(id) ON DELETE RESTRICT;


--
-- Name: biz_object_yhls nocode_fk_r_35467; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_yhls
    ADD CONSTRAINT nocode_fk_r_35467 FOREIGN KEY (c_yhzh_id) REFERENCES public.biz_object_yhzh(id) ON DELETE RESTRICT;


--
-- Name: biz_object_yhls nocode_fk_r_35469; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_yhls
    ADD CONSTRAINT nocode_fk_r_35469 FOREIGN KEY (c_gsmc_id) REFERENCES public.biz_object_gs(id) ON DELETE RESTRICT;


--
-- Name: biz_object_cssjdx nocode_fk_r_35473; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_cssjdx
    ADD CONSTRAINT nocode_fk_r_35473 FOREIGN KEY (c_yhls_id) REFERENCES public.biz_object_yhls(id) ON DELETE RESTRICT;


--
-- Name: biz_object_cssjdx nocode_fk_r_35475; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_cssjdx
    ADD CONSTRAINT nocode_fk_r_35475 FOREIGN KEY (c_yhmc_id) REFERENCES public.biz_object_yhlb(id) ON DELETE RESTRICT;


--
-- Name: biz_object_cssjdx nocode_fk_r_35477; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_cssjdx
    ADD CONSTRAINT nocode_fk_r_35477 FOREIGN KEY (c_yhzh_id) REFERENCES public.biz_object_yhzh(id) ON DELETE RESTRICT;


--
-- Name: biz_object_kjpzlr nocode_fk_r_38976; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_kjpzlr
    ADD CONSTRAINT nocode_fk_r_38976 FOREIGN KEY (c_zjls_id) REFERENCES public.biz_object_yhls(id) ON DELETE RESTRICT;


--
-- Name: biz_object_kjpzlr nocode_fk_r_38978; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_kjpzlr
    ADD CONSTRAINT nocode_fk_r_38978 FOREIGN KEY (c_yhzh_id) REFERENCES public.biz_object_yhzh(id) ON DELETE RESTRICT;


--
-- Name: biz_object_kjpzlr nocode_fk_r_38980; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_object_kjpzlr
    ADD CONSTRAINT nocode_fk_r_38980 FOREIGN KEY (c_gs_id) REFERENCES public.biz_object_gs(id) ON DELETE RESTRICT;


--
-- Name: biz_test_b1_4d33b085319540f3accounts nocode_fk_r_62063; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_test_b1_4d33b085319540f3accounts
    ADD CONSTRAINT nocode_fk_r_62063 FOREIGN KEY (parent_id) REFERENCES public.biz_test_b1_4d33b085319540f3companies(id) ON DELETE RESTRICT;


--
-- Name: biz_test_b1_4d33b085319540f3entries nocode_fk_r_62072; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.biz_test_b1_4d33b085319540f3entries
    ADD CONSTRAINT nocode_fk_r_62072 FOREIGN KEY (parent_id) REFERENCES public.biz_test_b1_4d33b085319540f3accounts(id) ON DELETE RESTRICT;


--
-- Name: nocode_flow_task_binding nocode_flow_task_binding_submission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_flow_task_binding
    ADD CONSTRAINT nocode_flow_task_binding_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES public.nocode_work_submission(id);


--
-- Name: nocode_handling_request nocode_handling_request_submission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_handling_request
    ADD CONSTRAINT nocode_handling_request_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES public.nocode_work_submission(id);


--
-- Name: nocode_index_definition nocode_index_definition_object_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_index_definition
    ADD CONSTRAINT nocode_index_definition_object_version_id_fkey FOREIGN KEY (object_version_id) REFERENCES public.nocode_object_version(id);


--
-- Name: nocode_object_application_grant nocode_object_application_grant_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant
    ADD CONSTRAINT nocode_object_application_grant_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id);


--
-- Name: nocode_object_application_grant_log nocode_object_application_grant_log_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant_log
    ADD CONSTRAINT nocode_object_application_grant_log_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id);


--
-- Name: nocode_object_application_grant_log nocode_object_application_grant_log_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant_log
    ADD CONSTRAINT nocode_object_application_grant_log_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_object_application_grant nocode_object_application_grant_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_application_grant
    ADD CONSTRAINT nocode_object_application_grant_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_object nocode_object_reconciliation_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object
    ADD CONSTRAINT nocode_object_reconciliation_version_id_fkey FOREIGN KEY (reconciliation_version_id) REFERENCES public.nocode_object_version(id);


--
-- Name: nocode_object_table nocode_object_table_object_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_table
    ADD CONSTRAINT nocode_object_table_object_version_id_fkey FOREIGN KEY (object_version_id) REFERENCES public.nocode_object_version(id);


--
-- Name: nocode_object_version nocode_object_version_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_object_version
    ADD CONSTRAINT nocode_object_version_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_publish_plan nocode_publish_plan_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_publish_plan
    ADD CONSTRAINT nocode_publish_plan_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_publish_plan nocode_publish_plan_object_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_publish_plan
    ADD CONSTRAINT nocode_publish_plan_object_version_id_fkey FOREIGN KEY (object_version_id) REFERENCES public.nocode_object_version(id);


--
-- Name: nocode_record_history_head nocode_record_history_head_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_history_head
    ADD CONSTRAINT nocode_record_history_head_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id) ON DELETE CASCADE;


--
-- Name: nocode_record_history nocode_record_history_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_history
    ADD CONSTRAINT nocode_record_history_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_record_history_head(object_id) ON DELETE CASCADE;


--
-- Name: nocode_record_process nocode_record_process_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_process
    ADD CONSTRAINT nocode_record_process_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id);


--
-- Name: nocode_record_process nocode_record_process_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_record_process
    ADD CONSTRAINT nocode_record_process_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_relation nocode_relation_object_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_relation
    ADD CONSTRAINT nocode_relation_object_version_id_fkey FOREIGN KEY (object_version_id) REFERENCES public.nocode_object_version(id);


--
-- Name: nocode_relation nocode_relation_target_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_relation
    ADD CONSTRAINT nocode_relation_target_object_id_fkey FOREIGN KEY (target_object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_report_dashboard nocode_report_dashboard_folder_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dashboard
    ADD CONSTRAINT nocode_report_dashboard_folder_id_fkey FOREIGN KEY (folder_id) REFERENCES public.nocode_report_folder(id);


--
-- Name: nocode_report_dashboard_version nocode_report_dashboard_version_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dashboard_version
    ADD CONSTRAINT nocode_report_dashboard_version_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES public.nocode_report_dashboard(id);


--
-- Name: nocode_report_dataset nocode_report_dataset_folder_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset
    ADD CONSTRAINT nocode_report_dataset_folder_id_fkey FOREIGN KEY (folder_id) REFERENCES public.nocode_report_folder(id);


--
-- Name: nocode_report_dataset_policy nocode_report_dataset_policy_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset_policy
    ADD CONSTRAINT nocode_report_dataset_policy_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.nocode_report_dataset(id);


--
-- Name: nocode_report_dataset_version nocode_report_dataset_version_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_dataset_version
    ADD CONSTRAINT nocode_report_dataset_version_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.nocode_report_dataset(id);


--
-- Name: nocode_report_folder nocode_report_folder_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_folder
    ADD CONSTRAINT nocode_report_folder_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.nocode_report_folder(id);


--
-- Name: nocode_report_object_grant nocode_report_object_grant_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_object_grant
    ADD CONSTRAINT nocode_report_object_grant_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.nocode_report_dataset(id);


--
-- Name: nocode_report_object_grant nocode_report_object_grant_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_object_grant
    ADD CONSTRAINT nocode_report_object_grant_object_id_fkey FOREIGN KEY (object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_report_preference nocode_report_preference_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_report_preference
    ADD CONSTRAINT nocode_report_preference_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES public.nocode_report_dashboard(id);


--
-- Name: nocode_resource_dependency nocode_resource_dependency_target_object_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_resource_dependency
    ADD CONSTRAINT nocode_resource_dependency_target_object_id_fkey FOREIGN KEY (target_object_id) REFERENCES public.nocode_object(id);


--
-- Name: nocode_task_comment nocode_task_comment_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_comment
    ADD CONSTRAINT nocode_task_comment_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.nocode_task_instance(id);


--
-- Name: nocode_task_entry_access nocode_task_entry_access_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_access
    ADD CONSTRAINT nocode_task_entry_access_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.nocode_application(id) ON DELETE CASCADE;


--
-- Name: nocode_task_entry_binding nocode_task_entry_binding_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_binding
    ADD CONSTRAINT nocode_task_entry_binding_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.nocode_task_instance(id);


--
-- Name: nocode_task_entry_record nocode_task_entry_record_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_entry_record
    ADD CONSTRAINT nocode_task_entry_record_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.nocode_task_instance(id);


--
-- Name: nocode_task_plan nocode_task_plan_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_plan
    ADD CONSTRAINT nocode_task_plan_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.nocode_task_instance(id);


--
-- Name: nocode_task_record_link nocode_task_record_link_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_task_record_link
    ADD CONSTRAINT nocode_task_record_link_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.nocode_task_instance(id);


--
-- Name: nocode_work_event nocode_work_event_submission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_event
    ADD CONSTRAINT nocode_work_event_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES public.nocode_work_submission(id);


--
-- Name: nocode_work_submission nocode_work_submission_draft_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nocode_work_submission
    ADD CONSTRAINT nocode_work_submission_draft_id_fkey FOREIGN KEY (draft_id) REFERENCES public.nocode_work_draft(id);


--
-- PostgreSQL database dump complete
--


