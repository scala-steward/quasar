/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package accounts

import com.precog.common.Path
import com.precog.common.security._

import org.joda.time.DateTime
import org.bson.types.ObjectId

trait AccountManager[M[+_]] {
  def newAccountId: M[AccountID]
  
  def newTempPassword(): String = new ObjectId().toString

  def updateAccount(account: Account): M[Boolean]
  def updateAccountPassword(account: Account, newPassword: String): M[Boolean]
 
  def newAccount(email: String, password: String, creationDate: DateTime, plan: AccountPlan)(f: (AccountID, Path) => M[APIKey]): M[Account]

  def listAccountIds(apiKey: APIKey) : M[Set[Account]]
  
  def findAccountById(accountId: AccountID): M[Option[Account]]
  def findAccountByEmail(email: String) : M[Option[Account]]
  def authAccount(email: String, password: String) : M[Option[Account]]
  
  def deleteAccount(accountId: AccountID): M[Option[Account]]

  def close(): M[Unit]
} 
