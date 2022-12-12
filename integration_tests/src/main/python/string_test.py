# Copyright (c) 2020-2022, NVIDIA CORPORATION.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import pytest

from asserts import assert_gpu_and_cpu_are_equal_collect, assert_gpu_and_cpu_are_equal_sql, \
    assert_gpu_sql_fallback_collect, assert_gpu_fallback_collect, assert_gpu_and_cpu_error, \
    assert_cpu_and_gpu_are_equal_collect_with_capture
from conftest import is_databricks_runtime
from data_gen import *
from marks import *
from pyspark.sql.types import *
import pyspark.sql.functions as f
from spark_session import is_before_spark_320

_regexp_conf = { 'spark.rapids.sql.regexp.enabled': 'true' }

def mk_str_gen(pattern):
    return StringGen(pattern).with_special_case('').with_special_pattern('.{0,10}')

def test_split_no_limit():
    data_gen = mk_str_gen('([ABC]{0,3}_?){0,7}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark : unary_op_df(spark, data_gen).selectExpr(
                'split(a, "AB")',
                'split(a, "C")',
                'split(a, "_")'),
                conf=_regexp_conf)

def test_split_negative_limit():
    data_gen = mk_str_gen('([ABC]{0,3}_?){0,7}')
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark : unary_op_df(spark, data_gen).selectExpr(
            'split(a, "AB", -1)',
            'split(a, "C", -2)',
            'split(a, "_", -999)'),
            conf=_regexp_conf)

def test_split_zero_limit():
    data_gen = mk_str_gen('([ABC]{0,3}_?){0,7}')
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark : unary_op_df(spark, data_gen).selectExpr(
            'split(a, "AB", 0)',
            'split(a, "C", 0)',
            'split(a, "_", 0)'),
        conf=_regexp_conf)

def test_split_one_limit():
    data_gen = mk_str_gen('([ABC]{0,3}_?){1,7}')
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark : unary_op_df(spark, data_gen).selectExpr(
            'split(a, "AB", 1)',
            'split(a, "C", 1)',
            'split(a, "_", 1)'),
        conf=_regexp_conf)

def test_split_positive_limit():
    data_gen = mk_str_gen('([ABC]{0,3}_?){0,7}')
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark : unary_op_df(spark, data_gen).selectExpr(
            'split(a, "AB", 2)',
            'split(a, "C", 3)',
            'split(a, "_", 999)'))

@pytest.mark.parametrize('data_gen,delim', [(mk_str_gen('([ABC]{0,3}_?){0,7}'), '_'),
    (mk_str_gen('([MNP_]{0,3}\\.?){0,5}'), '.'),
    (mk_str_gen('([123]{0,3}\\^?){0,5}'), '^')], ids=idfn)
def test_substring_index(data_gen,delim):
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark : unary_op_df(spark, data_gen).select(
                f.substring_index(f.col('a'), delim, 1),
                f.substring_index(f.col('a'), delim, 3),
                f.substring_index(f.col('a'), delim, 0),
                f.substring_index(f.col('a'), delim, -1),
                f.substring_index(f.col('a'), delim, -4)))

# ONLY LITERAL WIDTH AND PAD ARE SUPPORTED
def test_lpad():
    gen = mk_str_gen('.{0,5}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'LPAD(a, 2, " ")',
                'LPAD(a, NULL, " ")',
                'LPAD(a, 5, NULL)',
                'LPAD(a, 5, "G")',
                'LPAD(a, -1, "G")'))

# ONLY LITERAL WIDTH AND PAD ARE SUPPORTED
def test_rpad():
    gen = mk_str_gen('.{0,5}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'RPAD(a, 2, " ")',
                'RPAD(a, NULL, " ")',
                'RPAD(a, 5, NULL)',
                'RPAD(a, 5, "G")',
                'RPAD(a, -1, "G")'))

# ONLY LITERAL SEARCH PARAMS ARE SUPPORTED
def test_position():
    gen = mk_str_gen('.{0,3}Z_Z.{0,3}A.{0,3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'POSITION(NULL IN a)',
                'POSITION("Z_" IN a)',
                'POSITION("" IN a)',
                'POSITION("_" IN a)',
                'POSITION("A" IN a)'))

def test_locate():
    gen = mk_str_gen('.{0,3}Z_Z.{0,3}A.{0,3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'locate("Z", a, -1)',
                'locate("Z", a, 4)',
                'locate("A", a, 500)',
                'locate("_", a, NULL)'))

def test_instr():
    gen = mk_str_gen('.{0,3}Z_Z.{0,3}A.{0,3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'instr(a, "Z")',
                'instr(a, "A")',
                'instr(a, "_")',
                'instr(a, NULL)',
                'instr(NULL, "A")',
                'instr(NULL, NULL)'))

def test_contains():
    gen = mk_str_gen('.{0,3}Z?_Z?.{0,3}A?.{0,3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).select(
                f.col('a').contains('Z'),
                f.col('a').contains('Z_'),
                f.col('a').contains(''),
                f.col('a').contains(None)
                ))

def test_trim():
    gen = mk_str_gen('[Ab \ud720]{0,3}A.{0,3}Z[ Ab]{0,3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'TRIM(a)',
                'TRIM("Ab" FROM a)',
                'TRIM("A\ud720" FROM a)',
                'TRIM(BOTH NULL FROM a)',
                'TRIM("" FROM a)'))

def test_ltrim():
    gen = mk_str_gen('[Ab \ud720]{0,3}A.{0,3}Z[ Ab]{0,3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'LTRIM(a)',
                'LTRIM("Ab", a)',
                'TRIM(LEADING "A\ud720" FROM a)',
                'TRIM(LEADING NULL FROM a)',
                'TRIM(LEADING "" FROM a)'))

def test_rtrim():
    gen = mk_str_gen('[Ab \ud720]{0,3}A.{0,3}Z[ Ab]{0,3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'RTRIM(a)',
                'RTRIM("Ab", a)',
                'TRIM(TRAILING "A\ud720" FROM a)',
                'TRIM(TRAILING NULL FROM a)',
                'TRIM(TRAILING "" FROM a)'))

def test_startswith():
    gen = mk_str_gen('[Ab\ud720]{3}A.{0,3}Z[Ab\ud720]{3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).select(
                f.col('a').startswith('A'),
                f.col('a').startswith(''),
                f.col('a').startswith(None),
                f.col('a').startswith('A\ud720')))


def test_endswith():
    gen = mk_str_gen('[Ab\ud720]{3}A.{0,3}Z[Ab\ud720]{3}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).select(
                f.col('a').endswith('A'),
                f.col('a').endswith(''),
                f.col('a').endswith(None),
                f.col('a').endswith('A\ud720')))

def test_concat_ws_basic():
    gen = StringGen(nullable=True)
    (s1, s2) = gen_scalars(gen, 2, force_no_nulls=True)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: binary_op_df(spark, gen).select(
                f.concat_ws("-"),
                f.concat_ws("-", f.col('a')),
                f.concat_ws(None, f.col('a')),
                f.concat_ws("-", f.col('a'), f.col('b')),
                f.concat_ws("-", f.col('a'), f.lit('')),
                f.concat_ws("*", f.col('a'), f.col('b'), f.col('a')),
                f.concat_ws("*", s1, f.col('b')),
                f.concat_ws("+", f.col('a'), s2),
                f.concat_ws("-", f.lit(None), f.lit(None)),
                f.concat_ws("-", f.lit(None).cast('string'), f.col('b')),
                f.concat_ws("+", f.col('a'), f.lit(None).cast('string')),
                f.concat_ws(None, f.col('a'), f.col('b')),
                f.concat_ws("+", f.col('a'), f.lit(''))))

def test_concat_ws_arrays():
    gen = ArrayGen(StringGen(nullable=True), nullable=True)
    (s1, s2) = gen_scalars(gen, 2, force_no_nulls=True)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: binary_op_df(spark, gen).select(
                f.concat_ws("*", f.array(f.lit('2'), f.lit(''), f.lit('3'), f.lit('Z'))),
                f.concat_ws("*", s1, s2),
                f.concat_ws("-", f.array()),
                f.concat_ws("-", f.array(), f.lit('u')),
                f.concat_ws(None, f.lit('z'), s1, f.lit('b'), s2, f.array()),
                f.concat_ws("+", f.lit('z'), s1, f.lit('b'), s2, f.array()),
                f.concat_ws("*", f.col('b'), f.lit('z')),
                f.concat_ws("*", f.lit('z'), s1, f.lit('b'), s2, f.array(), f.col('b')),
                f.concat_ws("-", f.array(f.lit(None))),
                f.concat_ws("-", f.array(f.lit('')))))

def test_concat_ws_nulls_arrays():
    gen = ArrayGen(StringGen(nullable=True), nullable=True)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: binary_op_df(spark, gen).select(
                f.concat_ws("*", f.lit('z'), f.array(f.lit('2'), f.lit(None), f.lit('Z'))),
                f.concat_ws("*", f.array(f.lit(None), f.lit(None))),
                f.concat_ws("*", f.array(f.lit(None), f.lit(None)), f.col('b'), f.lit('a'))))

def test_concat_ws_sql_basic():
    gen = StringGen(nullable=True)
    assert_gpu_and_cpu_are_equal_sql(
            lambda spark: binary_op_df(spark, gen),
            'concat_ws_table',
            'select ' +
                'concat_ws("-"), ' +
                'concat_ws("-", a), ' +
                'concat_ws(null, a), ' +
                'concat_ws("-", a, b), ' +
                'concat_ws("-", null, null), ' +
                'concat_ws("+", \'aaa\', \'bbb\', \'zzz\'), ' +
                'concat_ws(null, b, \'aaa\', \'bbb\', \'zzz\'), ' +
                'concat_ws("=", b, \'\', \'bbb\', \'zzz\'), ' +
                'concat_ws("*", b, a, cast(null as string)) from concat_ws_table')

def test_concat_ws_sql_col_sep():
    gen = StringGen(nullable=True)
    sep = StringGen('[-,*,+,!]', nullable=True)
    assert_gpu_and_cpu_are_equal_sql(
            lambda spark: three_col_df(spark, gen, gen, sep),
            'concat_ws_table',
            'select ' +
                'concat_ws(c, a), ' +
                'concat_ws(c, a, b), ' +
                'concat_ws(c, null, null), ' +
                'concat_ws(c, \'aaa\', \'bbb\', \'zzz\'), ' +
                'concat_ws(c, b, \'\', \'bbb\', \'zzz\'), ' +
                'concat_ws(c, b, a, cast(null as string)) from concat_ws_table')


@pytest.mark.skipif(is_databricks_runtime(),
    reason='Databricks optimizes out concat_ws call in this case')
@allow_non_gpu('ProjectExec', 'Alias', 'ConcatWs')
def test_concat_ws_sql_col_sep_only_sep_specified():
    gen = StringGen(nullable=True)
    sep = StringGen('[-,*,+,!]', nullable=True)
    assert_gpu_sql_fallback_collect(
            lambda spark: three_col_df(spark, gen, gen, sep),
            'ConcatWs',
            'concat_ws_table',
            'select ' +
                'concat_ws(c) from concat_ws_table')

def test_concat_ws_sql_arrays():
    gen = ArrayGen(StringGen(nullable=True), nullable=True)
    assert_gpu_and_cpu_are_equal_sql(
            lambda spark: three_col_df(spark, gen, gen, StringGen(nullable=True)),
            'concat_ws_table',
            'select ' +
                'concat_ws("-", array()), ' +
                'concat_ws(null, c, c, array(c)), ' +
                'concat_ws("-", array(), c), ' +
                'concat_ws("-", a, b), ' +
                'concat_ws("-", a, array(null, c), b, array()), ' +
                'concat_ws("-", array(null, null)), ' +
                'concat_ws("-", a, array(null), b, array()), ' +
                'concat_ws("*", array(\'2\', \'\', \'3\', \'Z\', c)) from concat_ws_table')

def test_concat_ws_sql_arrays_col_sep():
    gen = ArrayGen(StringGen(nullable=True), nullable=True)
    sep = StringGen('[-,*,+,!]', nullable=True)
    assert_gpu_and_cpu_are_equal_sql(
            lambda spark: three_col_df(spark, gen, StringGen(nullable=True), sep),
            'concat_ws_table',
            'select ' +
                'concat_ws(c, array()) as emptyCon, ' +
                'concat_ws(c, b, b, array(b)), ' +
                'concat_ws(c, a, array(null, c), b, array()), ' +
                'concat_ws(c, array(null, null)), ' +
                'concat_ws(c, a, array(null), b, array()), ' +
                'concat_ws(c, array(\'2\', \'\', \'3\', \'Z\', b)) from concat_ws_table')

def test_concat_ws_sql_arrays_all_null_col_sep():
    gen = ArrayGen(StringGen(nullable=True), nullable=True)
    sep = NullGen()
    assert_gpu_and_cpu_are_equal_sql(
            lambda spark: three_col_df(spark, gen, StringGen(nullable=True), sep),
            'concat_ws_table',
            'select ' +
                'concat_ws(c, array(null, null)), ' +
                'concat_ws(c, a, array(null), b, array()), ' +
                'concat_ws(c, b, b, array(b)) from concat_ws_table')

def test_substring():
    gen = mk_str_gen('.{0,30}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'SUBSTRING(a, 1, 5)',
                'SUBSTRING(a, 5, 2147483647)',
                'SUBSTRING(a, 5, -2147483648)',
                'SUBSTRING(a, 1)',
                'SUBSTRING(a, -3)',
                'SUBSTRING(a, 3, -2)',
                'SUBSTRING(a, 100)',
                'SUBSTRING(a, -100)',
                'SUBSTRING(a, NULL)',
                'SUBSTRING(a, 1, NULL)',
                'SUBSTRING(a, -5, 0)',
                'SUBSTRING(a, -5, 4)',
                'SUBSTRING(a, 10, 0)',
                'SUBSTRING(a, -50, 10)',
                'SUBSTRING(a, -10, -1)',
                'SUBSTRING(a, 0, 10)',
                'SUBSTRING(a, 0, 0)'))

def test_substring_column():
    str_gen = mk_str_gen('.{0,30}')
    assert_gpu_and_cpu_are_equal_collect(
        lambda spark: three_col_df(spark, str_gen, int_gen, int_gen).selectExpr(
            'SUBSTRING(a, b, c)',
            'SUBSTRING(a, b, 0)',
            'SUBSTRING(a, b, 5)',
            'SUBSTRING(a, b, -5)',
            'SUBSTRING(a, b, 100)',
            'SUBSTRING(a, b, -100)',
            'SUBSTRING(a, b, NULL)',
            'SUBSTRING(a, 0, c)',
            'SUBSTRING(a, 5, c)',
            'SUBSTRING(a, -5, c)',
            'SUBSTRING(a, 100, c)',
            'SUBSTRING(a, -100, c)',
            'SUBSTRING(a, NULL, c)',
            'SUBSTRING(\'abc\', b, c)',
            'SUBSTRING(\'abc\', 1, c)',
            'SUBSTRING(\'abc\', 0, c)',
            'SUBSTRING(\'abc\', 5, c)',
            'SUBSTRING(\'abc\', -1, c)',
            'SUBSTRING(\'abc\', -5, c)',
            'SUBSTRING(\'abc\', NULL, c)',
            'SUBSTRING(\'abc\', b, 10)',
            'SUBSTRING(\'abc\', b, -10)',
            'SUBSTRING(\'abc\', b, 2)',
            'SUBSTRING(\'abc\', b, 0)',
            'SUBSTRING(\'abc\', b, NULL)',
            'SUBSTRING(\'abc\', b)',
            'SUBSTRING(a, b)'))

def test_repeat_scalar_and_column():
    gen_s = StringGen(nullable=False)
    gen_r = IntegerGen(min_val=-100, max_val=100, special_cases=[0], nullable=True)
    (s,) = gen_scalars_for_sql(gen_s, 1)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen_r).selectExpr(
                'repeat({}, a)'.format(s),
                'repeat({}, null)'.format(s)))

def test_repeat_column_and_scalar():
    gen_s = StringGen(nullable=True)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen_s).selectExpr(
                'repeat(a, -10)',
                'repeat(a, 0)',
                'repeat(a, 10)',
                'repeat(a, null)'
            ))

def test_repeat_null_column_and_scalar():
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: spark.range(100).selectExpr('CAST(NULL as STRING) AS a').selectExpr(
                'repeat(a, -10)',
                'repeat(a, 0)',
                'repeat(a, 10)'
            ))

def test_repeat_column_and_column():
    gen_s = StringGen(nullable=True)
    gen_r = IntegerGen(min_val=-100, max_val=100, special_cases=[0], nullable=True)
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: two_col_df(spark, gen_s, gen_r).selectExpr('repeat(a, b)'))

def test_replace():
    gen = mk_str_gen('.{0,5}TEST[\ud720 A]{0,5}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'REPLACE(a, "TEST", "PROD")',
                'REPLACE(a, "T\ud720", "PROD")',
                'REPLACE(a, "", "PROD")',
                'REPLACE(a, "T", NULL)',
                'REPLACE(a, NULL, "PROD")',
                'REPLACE(a, "T", "")'))

def test_length():
    gen = mk_str_gen('.{0,5}TEST[\ud720 A]{0,5}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'LENGTH(a)',
                'CHAR_LENGTH(a)',
                'CHARACTER_LENGTH(a)'))

def test_byte_length():
    gen = mk_str_gen('.{0,5}TEST[\ud720 A]{0,5}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'BIT_LENGTH(a)', 'OCTET_LENGTH(a)'))

@incompat
def test_initcap():
    # Because we don't use the same unicode version we need to limit
    # the charicter set to something more reasonable
    # upper and lower should cover the corner cases, this is mostly to
    # see if there are issues with spaces
    gen = mk_str_gen('([aAbB1357ȺéŸ_@%-]{0,15}[ \r\n\t]{1,2}){1,5}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).select(
                f.initcap(f.col('a'))))

@incompat
@pytest.mark.xfail(reason='Spark initcap will not convert ŉ to ʼN')
def test_initcap_special_chars():
    gen = mk_str_gen('ŉ([aAbB13ȺéŸ]{0,5}){1,5}')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).select(
                f.initcap(f.col('a'))))

def test_like_null():
    gen = mk_str_gen('.{0,3}a[|b*.$\r\n]{0,2}c.{0,3}')\
            .with_special_pattern('.{0,3}oo.{0,3}', weight=100.0)\
            .with_special_case('_')\
            .with_special_case('\r')\
            .with_special_case('\n')\
            .with_special_case('%SystemDrive%\\Users\\John')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).select(
                f.col('a').like('_'))) 

def test_like():
    gen = mk_str_gen('(\u20ac|\\w){0,3}a[|b*.$\r\n]{0,2}c\\w{0,3}')\
            .with_special_pattern('\\w{0,3}oo\\w{0,3}', weight=100.0)\
            .with_special_case('_')\
            .with_special_case('\r')\
            .with_special_case('\n')\
            .with_special_case('a{3}bar')\
            .with_special_case('12345678')\
            .with_special_case('12345678901234')\
            .with_special_case('%SystemDrive%\\Users\\John')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).select(
                f.col('a').like('%o%'), # turned into contains
                f.col('a').like('%a%'), # turned into contains
                f.col('a').like(''), #turned into equals
                f.col('a').like('12345678'), #turned into equals
                f.col('a').like('\\%SystemDrive\\%\\\\Users%'),
                f.col('a').like('_'),
                f.col('a').like('_oo_'),
                f.col('a').like('_oo%'),
                f.col('a').like('%oo_'),
                f.col('a').like('_\u201c%'),
                f.col('a').like('_a[d]%'),
                f.col('a').like('_a(d)%'),
                f.col('a').like('_$'),
                f.col('a').like('_$%'),
                f.col('a').like('_._'),
                f.col('a').like('_?|}{_%'),
                f.col('a').like('%a{3}%'))) 

def test_like_simple_escape():
    gen = mk_str_gen('(\u20ac|\\w){0,3}a[|b*.$\r\n]{0,2}c\\w{0,3}')\
            .with_special_pattern('\\w{0,3}oo\\w{0,3}', weight=100.0)\
            .with_special_case('_')\
            .with_special_case('\r')\
            .with_special_case('\n')\
            .with_special_case('a{3}bar')\
            .with_special_case('12345678')\
            .with_special_case('12345678901234')\
            .with_special_case('%SystemDrive%\\Users\\John')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'a like "_a^d%" escape "c"',
                'a like "a_a" escape "c"',
                'a like "a%a" escape "c"',
                'a like "c_" escape "c"',
                'a like x "6162632325616263" escape "#"',
                'a like x "61626325616263" escape "#"'))
 
def test_like_complex_escape():
    gen = mk_str_gen('(\u20ac|\\w){0,3}a[|b*.$\r\n]{0,2}c\\w{0,3}')\
            .with_special_pattern('\\w{0,3}oo\\w{0,3}', weight=100.0)\
            .with_special_case('_')\
            .with_special_case('\r')\
            .with_special_case('\n')\
            .with_special_case('a{3}bar')\
            .with_special_case('12345678')\
            .with_special_case('12345678901234')\
            .with_special_case('%SystemDrive%\\Users\\John')
    assert_gpu_and_cpu_are_equal_collect(
            lambda spark: unary_op_df(spark, gen).selectExpr(
                'a like x "256f6f5f"',
                'a like x "6162632325616263" escape "#"',
                'a like x "61626325616263" escape "#"',
                'a like ""',
                'a like "_oo_"',
                'a like "_oo%"',
                'a like "%oo_"',
                'a like "_\u20AC_"',
                'a like "\\%SystemDrive\\%\\\\\\\\Users%"',
                'a like "_oo"'),
            conf={'spark.sql.parser.escapedStringLiterals': 'true'})
 
